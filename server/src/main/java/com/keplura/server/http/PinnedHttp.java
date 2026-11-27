package com.keplura.server.http;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * One-shot, IP-pinned HTTP/1.1 exchange for admin targets that carry credentials (OpenAPI tool servers, OAuth
 * token endpoints). Same approach as {@link WebPageFetcher}: the host is resolved exactly once, EVERY answer must
 * pass the caller's route policy, and the socket connects to that checked address — it never resolves the host
 * again, so a DNS answer that changes between check and dial cannot reach another address. TLS verifies the
 * ORIGINAL host name (SNI + endpoint identification). No proxy, redirects, cookies, retries, compression or
 * ambient client state; one hard deadline for the whole exchange. Failures carry no address, header or body.
 */
public final class PinnedHttp {

    static final int MAX_HEADERS = 32_768;
    private static final ExecutorService WORKERS = Executors.newVirtualThreadPerTaskExecutor();

    @FunctionalInterface public interface Resolver { InetAddress[] resolve(String host) throws IOException; }
    @FunctionalInterface public interface Dialer { void connect(Socket socket, InetAddress address, int port, int millis) throws IOException; }

    /** {@code truncated}: the body exceeded the cap and was dropped (only when the caller asked to keep going). */
    public record Response(int status, Map<String, String> headers, byte[] body, boolean truncated) {
        public String header(String name) { return headers.getOrDefault(name.toLowerCase(Locale.ROOT), ""); }
    }

    /** Body cap for one response, chosen from its headers; {@code keepOversize}: report instead of failing. */
    public record Limit(int maxBytes, boolean keepOversize) { }

    /** The route policy refused an answer or the host did not resolve: nothing was sent. */
    public static final class Refused extends IOException {
        Refused() { super("target refused", null); }
    }

    /** The exchange ran past the deadline. */
    public static final class Timeout extends IOException {
        Timeout() { super("timeout", null); }
    }

    private final Resolver resolver;
    private final Dialer dialer;

    public PinnedHttp() {
        this(InetAddress::getAllByName, (socket, address, port, millis) ->
                socket.connect(new InetSocketAddress(address, port), millis));
    }

    /** Test seam: the route policy still sees the resolved answer; only the checked dial may be redirected. */
    public PinnedHttp(Resolver resolver, Dialer dialer) {
        this.resolver = resolver;
        this.dialer = dialer;
    }

    /**
     * Exactly one request. {@code headers} must not contain Host, Content-Length, Connection or Transfer-Encoding.
     * {@code limit} picks the body cap from the 2xx response headers; a body above a {@code keepOversize} cap is
     * reported as {@code truncated} instead of failing.
     */
    public Response send(String method, URI uri, Map<String, String> headers, byte[] body,
                         java.util.function.Function<Map<String, String>, Limit> limit, Duration timeout,
                         Predicate<InetAddress> routeAllowed) throws IOException {
        Session session = new Session();
        Future<Response> task = WORKERS.submit(() -> exchange(method, uri, headers, body, limit,
                (int) timeout.toMillis(), routeAllowed, session));
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", null);
        } catch (TimeoutException e) {
            throw new Timeout();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Refused r) throw r;
            if (e.getCause() instanceof IOException io) throw new IOException("exchange failed", sanitized(io));
            throw new IOException("exchange failed", null);
        } finally {
            session.close();
            task.cancel(true);
        }
    }

    /** Never keep socket messages (they can name addresses). */
    private static IOException sanitized(IOException e) {
        return e instanceof javax.net.ssl.SSLException ? new javax.net.ssl.SSLException("tls failed") : null;
    }

    private Response exchange(String method, URI uri, Map<String, String> headers, byte[] body,
                              java.util.function.Function<Map<String, String>, Limit> limit, int millis, Predicate<InetAddress> routeAllowed, Session session) throws IOException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) throw new Refused();
        if (uri.getRawUserInfo() != null || uri.getHost() == null || !method.matches("[A-Z]{3,7}")) throw new Refused();
        String host = uri.getHost().replace("[", "").replace("]", "");
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (IOException e) {
            throw new Refused();
        }
        if (addresses == null || addresses.length == 0) throw new Refused();
        for (InetAddress address : addresses) {
            if (!routeAllowed.test(address)) throw new Refused();
        }
        int port = uri.getPort() < 0 ? (scheme.equals("https") ? 443 : 80) : uri.getPort();
        try (Socket socket = new Socket(Proxy.NO_PROXY)) {
            session.register(socket);
            socket.setSoTimeout(millis);
            dialer.connect(socket, addresses[0], port, millis);
            Socket transport = socket;
            if (scheme.equals("https")) {
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(socket, host, port, true);
                session.register(tls);
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                if (!host.contains(":") && !host.matches("[0-9.]+")) parameters.setServerNames(List.of(new SNIHostName(host)));
                tls.setSSLParameters(parameters);
                tls.startHandshake();
                transport = tls;
            }
            try (Socket connection = transport) {
                String path = uri.getRawPath();
                if (path == null || path.isEmpty()) path = "/";
                if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
                String authority = (host.contains(":") ? "[" + host + "]" : host) + (uri.getPort() < 0 ? "" : ":" + port);
                StringBuilder request = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\nHost: ")
                        .append(authority).append("\r\n");
                for (var h : headers.entrySet()) {
                    String key = h.getKey();
                    if (!key.matches("[A-Za-z0-9-]{1,64}") || Set.of("host", "content-length", "connection", "transfer-encoding")
                            .contains(key.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("header");
                    String value = h.getValue();
                    if (value.chars().anyMatch(c -> c < 0x20 && c != '\t' || c == 0x7f)) throw new IllegalArgumentException("header");
                    request.append(key).append(": ").append(value).append("\r\n");
                }
                byte[] payload = body == null ? new byte[0] : body;
                if (body != null || !Set.of("GET", "HEAD", "DELETE").contains(method)) {
                    request.append("Content-Length: ").append(payload.length).append("\r\n");
                }
                request.append("Accept-Encoding: identity\r\nConnection: close\r\n\r\n");
                var out = connection.getOutputStream();
                out.write(request.toString().getBytes(StandardCharsets.UTF_8));
                out.write(payload);
                out.flush();
                InputStream in = new BufferedInputStream(connection.getInputStream(), 4096);
                Header header = readHeaders(in);
                if (header.status() / 100 != 2) return new Response(header.status(), header.values(), new byte[0], false);
                if (!header.values().getOrDefault("content-encoding", "identity").equalsIgnoreCase("identity")) {
                    throw new IOException("encoded body", null);
                }
                Limit cap = limit.apply(header.values());
                try {
                    byte[] data = readBody(in, header.values(), cap.maxBytes());
                    session.check();
                    return new Response(header.status(), header.values(), data, false);
                } catch (Oversize e) {
                    if (!cap.keepOversize()) throw new IOException("body too large", null);
                    return new Response(header.status(), header.values(), new byte[0], true);
                }
            }
        }
    }

    record Header(int status, Map<String, String> values) { }

    static final class Oversize extends IOException {
        Oversize() { super("oversize", null); }
    }

    static Header readHeaders(InputStream in) throws IOException {
        int[] budget = {MAX_HEADERS};
        String status = line(in, budget);
        if (!status.matches("HTTP/1\\.[01] [0-9]{3}( .*)?")) throw new IOException("bad status line", null);
        Map<String, String> headers = new HashMap<>();
        for (String row; !(row = line(in, budget)).isEmpty();) {
            int colon = row.indexOf(':');
            if (colon < 1 || !row.substring(0, colon).matches("[A-Za-z0-9-]+")) throw new IOException("bad header", null);
            String key = row.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = row.substring(colon + 1).strip();
            if (headers.putIfAbsent(key, value) != null
                    && Set.of("location", "content-type", "content-length", "transfer-encoding", "content-encoding").contains(key)) {
                throw new IOException("duplicate header", null);
            }
        }
        return new Header(Integer.parseInt(status.substring(9, 12)), headers);
    }

    private static String line(InputStream in, int[] budget) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (--budget[0] >= 0) {
            int b = in.read();
            if (b == -1) throw new IOException("eof", null);
            if (b == '\r') {
                if (--budget[0] < 0 || in.read() != '\n') throw new IOException("bad line", null);
                return out.toString(StandardCharsets.ISO_8859_1);
            }
            if (b < 32 && b != '\t' || b == 127) throw new IOException("bad line", null);
            out.write(b);
        }
        throw new IOException("headers too large", null);
    }

    static byte[] readBody(InputStream in, Map<String, String> headers, int maxBytes) throws IOException {
        String transfer = headers.get("transfer-encoding");
        String length = headers.get("content-length");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (transfer != null) {
            if (!transfer.equalsIgnoreCase("chunked") || length != null) throw new IOException("bad framing", null);
            int[] overhead = {MAX_HEADERS};
            for (;;) {
                String size = line(in, overhead).split(";", 2)[0];
                if (!size.matches("[0-9a-fA-F]{1,8}")) throw new IOException("bad chunk", null);
                long count = Long.parseLong(size, 16);
                if (count > maxBytes - out.size()) throw new Oversize();
                if (count == 0) {
                    while (!line(in, overhead).isEmpty()) { /* bounded trailers, never used */ }
                    break;
                }
                copy(in, out, (int) count);
                if (!line(in, overhead).isEmpty()) throw new IOException("bad chunk", null);
            }
        } else if (length != null) {
            if (!length.matches("[0-9]{1,10}")) throw new IOException("bad length", null);
            long count = Long.parseLong(length);
            if (count > maxBytes) throw new Oversize();
            copy(in, out, (int) count);
        } else {
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf, 0, buf.length)) != -1;) {
                if (out.size() + n > maxBytes) throw new Oversize();
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
    }

    private static void copy(InputStream in, ByteArrayOutputStream out, int count) throws IOException {
        byte[] buf = new byte[4096];
        while (count > 0) {
            int n = in.read(buf, 0, Math.min(buf.length, count));
            if (n < 0) throw new IOException("eof", null);
            out.write(buf, 0, n);
            count -= n;
        }
    }

    private static final class Session implements AutoCloseable {
        private Socket socket;
        private boolean closed;
        synchronized void check() throws IOException { if (closed || Thread.currentThread().isInterrupted()) throw new IOException("closed", null); }
        synchronized void register(Socket next) throws IOException {
            if (closed) { next.close(); throw new IOException("closed", null); }
            socket = next;
        }
        @Override public synchronized void close() {
            closed = true;
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        }
    }
}
