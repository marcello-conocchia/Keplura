package com.keplura.server.http;

import com.keplura.server.admin.SsrfGuard;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** A one-shot, IP-pinned HTTP/1.1 GET. No proxy, cookies, credentials, retries or ambient client state. */
@Component
public class WebPageFetcher {
    static final int MAX_BYTES = 2_000_000;
    private static final int MAX_HEADERS = 32_768;
    private static final ExecutorService WORKERS = Executors.newVirtualThreadPerTaskExecutor();
    private final Resolver resolver;
    private final Dialer dialer;
    private final Duration timeout;

    @FunctionalInterface interface Resolver { InetAddress[] resolve(String host) throws IOException; }
    @FunctionalInterface interface Dialer { void connect(Socket socket, InetAddress address, int port, int millis) throws IOException; }
    public record Page(String title, String url, String text) { }

    public WebPageFetcher() {
        this(InetAddress::getAllByName, (socket, address, port, millis) ->
                socket.connect(new InetSocketAddress(address, port), millis), Duration.ofSeconds(10));
    }

    // Package-only test seam: policy still checks public routes; only the already checked dial is redirected locally.
    WebPageFetcher(Resolver resolver, Dialer dialer, Duration timeout) {
        this.resolver = resolver;
        this.dialer = dialer;
        this.timeout = timeout;
    }

    public Page fetch(String url, List<String> blockedDomains) throws IOException {
        Session session = new Session();
        Future<Page> task = WORKERS.submit(() -> load(url, List.copyOf(blockedDomains), session));
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw refused();
        } catch (ExecutionException | TimeoutException e) {
            throw refused(); // never expose DNS, headers, body or socket errors
        } finally {
            session.close();
            task.cancel(true);
        }
    }

    private Page load(String raw, List<String> blocked, Session session) throws IOException {
        URI uri = shape(raw, blocked);
        for (int redirects = 0; ; redirects++) {
            String host = uri.getHost().replace("[", "").replace("]", "");
            // Resolve exactly once per hop. Every answer must be public; the socket never resolves the host again.
            InetAddress[] addresses = resolver.resolve(host);
            if (addresses.length == 0) throw refused();
            for (InetAddress address : addresses) {
                if (!SsrfGuard.publicRouteAllowed(address)
                        || (host.contains(":") && address.getAddress().length == 4)) throw refused();
            }
            int port = uri.getPort() < 0 ? (uri.getScheme().equals("https") ? 443 : 80) : uri.getPort();
            try (Socket socket = new Socket()) {
                session.register(socket);
                socket.setSoTimeout((int) timeout.toMillis());
                dialer.connect(socket, addresses[0], port, (int) timeout.toMillis());
                Socket transport = socket;
                if (uri.getScheme().equals("https")) {
                    SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                            .createSocket(socket, host, port, true);
                    session.register(tls);
                    SSLParameters parameters = tls.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    if (!host.contains(":") && !host.matches("[0-9.]+"))
                        parameters.setServerNames(List.of(new SNIHostName(host)));
                    tls.setSSLParameters(parameters);
                    tls.startHandshake();
                    transport = tls;
                }
                try (Socket connection = transport) {
                    String path = uri.getRawPath();
                    if (path == null || path.isEmpty()) path = "/";
                    if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
                    String authority = uri.getHost() + (uri.getPort() < 0 ? "" : ":" + port);
                    String request = "GET " + path + " HTTP/1.1\r\nHost: " + authority
                            + "\r\nUser-Agent: Keplura-WebFetch/1.0\r\nAccept: text/html, text/plain, application/xhtml+xml"
                            + "\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n";
                    connection.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                    InputStream in = new BufferedInputStream(connection.getInputStream(), 4096);
                    Header header = headers(in);
                    if (Set.of(301, 302, 303, 307, 308).contains(header.status())) {
                        if (redirects >= 3 || !header.values().containsKey("location")) throw refused();
                        try { uri = shape(uri.resolve(header.values().get("location")).toASCIIString(), blocked); }
                        catch (IllegalArgumentException e) { throw refused(); }
                        continue; // close without consuming a redirect body
                    }
                    if (header.status() != 200) throw refused();
                    String type = header.values().getOrDefault("content-type", "");
                    String mime = type.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
                    if (!Set.of("text/html", "text/plain", "application/xhtml+xml").contains(mime)) throw refused();
                    if (!header.values().getOrDefault("content-encoding", "identity").equalsIgnoreCase("identity")) throw refused();
                    byte[] body = body(in, header.values());
                    session.check();
                    Charset charset = null;
                    for (String param : type.split(";")) {
                        if (param.strip().toLowerCase(Locale.ROOT).startsWith("charset=")) {
                            try { charset = Charset.forName(param.strip().substring(8).replace("\"", "")); }
                            catch (IllegalArgumentException e) { throw refused(); }
                        }
                    }
                    if (mime.equals("text/plain")) {
                        return new Page("Webseite", uri.toASCIIString(), clean(new String(body, charset == null ? StandardCharsets.UTF_8 : charset)));
                    }
                    // Jsoup parses bytes only: no network fetch, scripts, external entities or subresources.
                    var document = Jsoup.parse(new ByteArrayInputStream(body), charset == null ? null : charset.name(), uri.toASCIIString());
                    String title = clean(document.title());
                    document.select("script,style,noscript,template").remove();
                    return new Page(title, uri.toASCIIString(), clean(document.body().text()));
                }
            }
        }
    }

    private static URI shape(String raw, List<String> blocked) throws IOException {
        try {
            URI uri = new URI(raw);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 80 && uri.getPort() != 443)
                    || !uri.toASCIIString().equals(raw) || raw.length() > 4096) throw refused();
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            // Reject alternate numeric spellings before DNS (including octal and hex IPv4 forms).
            if (Arrays.stream(host.split("\\.")).allMatch(p -> p.matches("(?i)([0-9]+|0x[0-9a-f]+)"))
                    && !host.matches("(0|[1-9][0-9]{0,2})(\\.(0|[1-9][0-9]{0,2})){3}")) throw refused();
            String canonicalHost = host.replaceAll("\\.$", "");
            for (String domain : blocked) {
                String d = domain.toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
                if (canonicalHost.equals(d) || canonicalHost.endsWith("." + d)) throw refused();
            }
            return uri;
        } catch (URISyntaxException | IllegalArgumentException e) { throw refused(); }
    }

    private record Header(int status, Map<String, String> values) { }

    private static Header headers(InputStream in) throws IOException {
        int[] budget = {MAX_HEADERS};
        String status = line(in, budget);
        if (!status.matches("HTTP/1\\.[01] [0-9]{3}( .*)?")) throw refused();
        Map<String, String> headers = new HashMap<>();
        for (String row; !(row = line(in, budget)).isEmpty();) {
            int colon = row.indexOf(':');
            if (colon < 1 || !row.substring(0, colon).matches("[A-Za-z0-9-]+")) throw refused();
            String key = row.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = row.substring(colon + 1).strip();
            if (headers.putIfAbsent(key, value) != null
                    && Set.of("location", "content-type", "content-length", "transfer-encoding", "content-encoding").contains(key))
                throw refused();
        }
        return new Header(Integer.parseInt(status.substring(9, 12)), headers);
    }

    private static String line(InputStream in, int[] budget) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (--budget[0] >= 0) {
            int b = in.read();
            if (b == -1) throw refused();
            if (b == '\r') {
                if (--budget[0] < 0 || in.read() != '\n') throw refused();
                return out.toString(StandardCharsets.ISO_8859_1);
            }
            if (b < 32 && b != '\t' || b == 127) throw refused();
            out.write(b);
        }
        throw refused();
    }

    private static byte[] body(InputStream in, Map<String, String> headers) throws IOException {
        String transfer = headers.get("transfer-encoding");
        String length = headers.get("content-length");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (transfer != null) {
            if (!transfer.equalsIgnoreCase("chunked") || length != null) throw refused();
            int[] overhead = {MAX_HEADERS}; // also bounds chunk extensions, framing and trailers
            for (;;) {
                String size = line(in, overhead).split(";", 2)[0];
                if (!size.matches("[0-9a-fA-F]{1,8}")) throw refused();
                long count = Long.parseLong(size, 16);
                if (count > MAX_BYTES - out.size()) throw refused();
                if (count == 0) {
                    while (!line(in, overhead).isEmpty()) { /* bounded trailers, never used */ }
                    break;
                }
                copy(in, out, (int) count);
                if (!line(in, overhead).isEmpty()) throw refused();
            }
        } else if (length != null) {
            if (!length.matches("[0-9]{1,10}")) throw refused();
            long count = Long.parseLong(length);
            if (count > MAX_BYTES) throw refused();
            copy(in, out, (int) count);
        } else {
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf, 0, Math.min(buf.length, MAX_BYTES - out.size() + 1))) != -1;) {
                if (out.size() + n > MAX_BYTES) throw refused();
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
    }

    private static void copy(InputStream in, ByteArrayOutputStream out, int count) throws IOException {
        byte[] buf = new byte[4096];
        while (count > 0) {
            int n = in.read(buf, 0, Math.min(buf.length, count));
            if (n < 0) throw refused();
            out.write(buf, 0, n);
            count -= n;
        }
    }

    private static String clean(String value) { return value.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ").strip(); }
    private static IOException refused() { return new IOException("Webseitenabruf nicht verfügbar."); }

    private static final class Session implements AutoCloseable {
        private Socket socket;
        private boolean closed;
        synchronized void check() throws IOException { if (closed || Thread.currentThread().isInterrupted()) throw refused(); }
        synchronized void register(Socket next) throws IOException {
            if (closed) { next.close(); throw refused(); }
            socket = next;
        }
        @Override public synchronized void close() {
            closed = true;
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        }
    }
}
