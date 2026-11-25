package com.keplura.server.http;

import com.keplura.server.admin.SsrfGuard;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class WebPageFetcherTest {
    private HttpServer server;
    private final AtomicInteger connections = new AtomicInteger();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile int status = 200;
    private volatile String type = "text/html; charset=UTF-8";
    private volatile String location;
    private volatile byte[] body = "<title>Quelle</title><style>STYLE_SECRET</style><script>SCRIPT_SECRET</script><p>Seitentext\u0001</p>".getBytes(StandardCharsets.UTF_8);
    private volatile int delay;
    private volatile boolean chunked;
    private volatile com.sun.net.httpserver.Headers requestHeaders;
    private WebPageFetcher fetcher;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            requestHeaders = exchange.getRequestHeaders();
            try {
                if (delay > 0) Thread.sleep(delay);
                exchange.getResponseHeaders().set("Content-Type", type);
                if (location != null) exchange.getResponseHeaders().set("Location", location);
                exchange.sendResponseHeaders(status, chunked ? 0 : body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (IOException ignored) { /* client must close on bounds / redirects */ }
            finally { exchange.close(); }
        });
        server.start();
        fetcher = WebPageFetcherTestSupport.local(server, connections);
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void htmlIsParsedAsDataAndRequestsHaveNoAmbientCredentials() throws Exception {
        var page = fetcher.fetch("http://example.org/page?q=test", List.of());
        assertThat(page.title()).isEqualTo("Quelle");
        assertThat(page.url()).isEqualTo("http://example.org/page?q=test");
        assertThat(page.text()).contains("Seitentext").doesNotContain("SCRIPT_SECRET", "STYLE_SECRET", "\u0001");
        assertThat(requestHeaders.getFirst("Host")).isEqualTo("example.org");
        assertThat(requestHeaders.getFirst("Cookie")).isNull();
        assertThat(requestHeaders.getFirst("Authorization")).isNull();
        assertThat(requestHeaders.getFirst("User-Agent")).isEqualTo("Keplura-WebFetch/1.0");
        assertThat(connections).hasValue(1);
    }

    @Test void acceptsPlainTextCharsetAndChunkedBodies() throws Exception {
        type = "text/plain; charset=ISO-8859-1";
        body = "Grüße".getBytes(StandardCharsets.ISO_8859_1);
        chunked = true;
        assertThat(fetcher.fetch("http://example.org/", List.of()).text()).isEqualTo("Grüße");
    }

    @ParameterizedTest @ValueSource(strings = {"text/html", "application/xhtml+xml"})
    void honorsTheHttpCharsetForHtmlWithoutAMetaTag(String mime) throws Exception {
        type = mime + "; charset=ISO-8859-1";
        body = "<title>Grüße</title><p>öffentliche Quelle</p>".getBytes(StandardCharsets.ISO_8859_1);
        var page = fetcher.fetch("http://example.org/", List.of());
        assertThat(page.title()).isEqualTo("Grüße");
        assertThat(page.text()).contains("öffentliche Quelle");
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.0.1", "169.254.169.254", "100.64.0.1",
            "0.0.0.0", "::1", "fc00::1", "fe80::1", "::", "224.0.0.1", "::ffff:127.0.0.1"})
    void internalAnswersAreRefusedBeforeAnyConnection(String ip) throws Exception {
        fetcher = new WebPageFetcher(host -> new InetAddress[]{InetAddress.getByName(ip)},
                (s, a, p, t) -> connections.incrementAndGet(), Duration.ofSeconds(1));
        assertThatThrownBy(() -> fetcher.fetch("http://example.org/", List.of())).isInstanceOf(IOException.class);
        assertThat(connections).hasValue(0);
        assertThat(requests).hasValue(0);
    }

    static java.util.stream.Stream<String> embeddedOrSpecialRoutes() {
        return java.util.stream.Stream.of(
                "64:ff9b::7f00:1", "64:ff9b::a9fe:a9fe", "64:ff9b::a00:1", "64:ff9b::6440:1",
                "2002:7f00:1::1", "2002:a9fe:a9fe::1", "2002:c0a8:1::1", "2002:6440:1::1",
                "::127.0.0.1", "::169.254.169.254", "::10.0.0.1", "::100.64.0.1",
                "64:ff9b:1::7f00:1", "64:ff9b:1::a9fe:a9fe", "64:ff9b:1:7f00:1::", "64:ff9b:1:a9fe:fe:a900::",
                "0.0.0.1", "0.255.255.255", "240.0.0.0", "255.255.255.255", "198.18.0.0", "198.19.255.255",
                "64:ff9b::1", "64:ff9b::f000:1", "2002:c612:1::1");
    }

    @ParameterizedTest @MethodSource("embeddedOrSpecialRoutes")
    void embeddedAndSpecialLiteralsNeverReachTheDialer(String ip) {
        fetcher = new WebPageFetcher(InetAddress::getAllByName,
                (s, a, p, t) -> connections.incrementAndGet(), Duration.ofSeconds(1));
        String url = "http://" + (ip.contains(":") ? "[" + ip + "]" : ip) + "/";
        assertThatThrownBy(() -> fetcher.fetch(url, List.of())).isInstanceOf(IOException.class);
        assertThat(connections).hasValue(0);
        assertThat(requests).hasValue(0);
    }

    @ParameterizedTest @MethodSource("embeddedOrSpecialRoutes")
    void embeddedAndSpecialDnsAnswersNeverReachTheDialer(String ip) {
        fetcher = new WebPageFetcher(host -> new InetAddress[]{InetAddress.getByName(ip)},
                (s, a, p, t) -> connections.incrementAndGet(), Duration.ofSeconds(1));
        assertThatThrownBy(() -> fetcher.fetch("http://example.org/", List.of())).isInstanceOf(IOException.class);
        assertThat(connections).hasValue(0);
        assertThat(requests).hasValue(0);
    }

    @Test void ambientSocksProxySelectorIsNeverUsed() throws Exception {
        var original = ProxySelector.getDefault();
        var selected = new AtomicInteger();
        try {
            ProxySelector.setDefault(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) {
                    selected.incrementAndGet();
                    return List.of(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", server.getAddress().getPort())));
                }
                @Override public void connectFailed(URI uri, SocketAddress sa, IOException e) { }
            });
            assertThat(fetcher.fetch("http://example.org/", List.of()).text()).contains("Seitentext");
            assertThat(selected).hasValue(0);
            assertThat(connections).hasValue(1);
        } finally { ProxySelector.setDefault(original); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://2130706433/", "http://0177.0.0.1/", "http://0x7f000001/", "http://0x7f.0.0.1/",
            "http://127.1/", "http://user:pass@example.org/", "ftp://example.org/", "http://example.org:8080/",
            "http://example.org:0/", "http://example.org/%0d%0aX:bad\r\n", "http://[::ffff:8.8.8.8]/"})
    void forbiddenUrlShapesNeverConnect(String url) {
        // Real resolver: unlike the test-only public-domain resolver, literal IPs must not be rewritten.
        fetcher = new WebPageFetcher(InetAddress::getAllByName,
                (s, a, p, t) -> connections.incrementAndGet(), Duration.ofSeconds(1));
        assertThatThrownBy(() -> fetcher.fetch(url, List.of())).isInstanceOf(IOException.class);
        assertThat(connections).hasValue(0);
    }

    @Test void checksEveryAnswerAndAdminExceptionsCannotOpenPageRoutes() throws Exception {
        var privateIp = InetAddress.getByName("127.0.0.1");
        assertThat(SsrfGuard.publicRouteAllowed(privateIp)).isFalse();
        new SsrfGuard("127.0.0.0/8").validateForDial("http://127.0.0.1"); // admin test IS allowed
        fetcher = new WebPageFetcher(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8"), privateIp},
                (s, a, p, t) -> connections.incrementAndGet(), Duration.ofSeconds(1));
        assertThatThrownBy(() -> fetcher.fetch("http://example.org/", List.of())).isInstanceOf(IOException.class);
        assertThat(connections).hasValue(0);
    }

    @Test void dnsIsResolvedOnceAndOnlyTheCheckedAddressIsDialled() throws Exception {
        var resolutions = new AtomicInteger();
        var publicIp = InetAddress.getByName("8.8.8.8");
        fetcher = new WebPageFetcher(host -> new InetAddress[]{resolutions.incrementAndGet() == 1 ? publicIp : InetAddress.getLoopbackAddress()},
                (s, address, port, millis) -> {
                    assertThat(address).isEqualTo(publicIp);
                    assertThat(port).isEqualTo(80);
                    connections.incrementAndGet();
                    s.connect(new InetSocketAddress("127.0.0.1", server.getAddress().getPort()), millis);
                }, Duration.ofSeconds(1));
        assertThat(fetcher.fetch("http://example.org/", List.of()).text()).contains("Seitentext");
        assertThat(resolutions).hasValue(1);
        assertThat(connections).hasValue(1);
    }

    @Test void redirectRechecksDnsAndClosesWithoutDiallingInternalTarget() throws Exception {
        status = 302; location = "http://private.example/";
        fetcher = new WebPageFetcher(host -> new InetAddress[]{InetAddress.getByName(host.equals("example.org") ? "8.8.8.8" : "127.0.0.1")},
                (s, a, p, t) -> {
                    connections.incrementAndGet();
                    s.connect(new InetSocketAddress("127.0.0.1", server.getAddress().getPort()), t);
                }, Duration.ofSeconds(1));
        assertThatThrownBy(() -> fetcher.fetch("http://example.org/", List.of())).isInstanceOf(IOException.class);
        assertThat(connections).hasValue(1);
    }

    @Test void redirectLimitIsThreeAndRelativeLocationsAreSupported() {
        status = 302; location = "/next";
        assertThatThrownBy(() -> fetcher.fetch("http://example.org/", List.of())).isInstanceOf(IOException.class);
        assertThat(connections).hasValue(4);
    }

    @Test void blockedDomainIncludesSubdomainsTrailingDotAndRedirects() {
        assertThatThrownBy(() -> fetcher.fetch("http://sub.blocked.test./", List.of("blocked.test"))).isInstanceOf(IOException.class);
        assertThat(connections).hasValue(0);
        status = 302; location = "http://blocked.test/";
        assertThatThrownBy(() -> fetcher.fetch("http://example.org/", List.of("blocked.test"))).isInstanceOf(IOException.class);
        assertThat(connections).hasValue(1);
    }

    @Test void rejectsContentLengthAboveBudgetAndChunkedBodiesAtTheSameLimit() {
        body = new byte[WebPageFetcher.MAX_BYTES + 1];
        assertThatThrownBy(() -> fetcher.fetch("http://example.org/", List.of())).isInstanceOf(IOException.class);
        chunked = true;
        assertThatThrownBy(() -> fetcher.fetch("http://example.org/", List.of())).isInstanceOf(IOException.class);
    }

    @Test void rejectsWrongTypeAndHttpErrorWithFixedText() {
        type = "application/pdf";
        assertThatThrownBy(() -> fetcher.fetch("http://example.org/", List.of())).hasMessage("Webseitenabruf nicht verfügbar.");
        status = 500;
        body = "PRIVATE_RESPONSE_BODY".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> fetcher.fetch("http://example.org/", List.of())).hasMessage("Webseitenabruf nicht verfügbar.");
    }

    @Test void deadlineIncludesDnsAndPreventsLateConnections() throws Exception {
        fetcher = new WebPageFetcher(host -> {
            try { Thread.sleep(500); } catch (InterruptedException e) { /* model a resolver that returns late */ }
            return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
        }, (s, a, p, t) -> connections.incrementAndGet(), Duration.ofMillis(60));
        long start = System.nanoTime();
        assertThatThrownBy(() -> fetcher.fetch("http://example.org/", List.of())).isInstanceOf(IOException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(400));
        Thread.sleep(100);
        assertThat(connections).hasValue(0);
    }

    @Test void totalDeadlineAlsoClosesAStalledSocket() {
        delay = 800;
        fetcher = new WebPageFetcher(host -> new InetAddress[]{InetAddress.getByName("8.8.8.8")},
                (s, a, p, t) -> s.connect(new InetSocketAddress("127.0.0.1", server.getAddress().getPort()), t), Duration.ofMillis(100));
        long start = System.nanoTime();
        assertThatThrownBy(() -> fetcher.fetch("http://example.org/", List.of())).isInstanceOf(IOException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(600));
    }

    @Test @SuppressWarnings("deprecation")
    void tlsUsesTheOriginalHostnameAndRefusesAMismatchOnTheSamePinnedIp() throws Exception {
        var keys = java.security.KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        var pair = keys.generateKeyPair();
        var generator = new org.bouncycastle.x509.X509V3CertificateGenerator();
        var name = new javax.security.auth.x500.X500Principal("CN=example.org");
        generator.setIssuerDN(name); generator.setSubjectDN(name);
        generator.setSerialNumber(java.math.BigInteger.ONE);
        generator.setNotBefore(new java.util.Date(System.currentTimeMillis() - 60_000));
        generator.setNotAfter(new java.util.Date(System.currentTimeMillis() + 3_600_000));
        generator.setPublicKey(pair.getPublic()); generator.setSignatureAlgorithm("SHA256withRSA");
        generator.addExtension(org.bouncycastle.asn1.x509.Extension.subjectAlternativeName, false,
                new org.bouncycastle.asn1.x509.GeneralNames(new org.bouncycastle.asn1.x509.GeneralName(
                        org.bouncycastle.asn1.x509.GeneralName.dNSName, "example.org")));
        var certificate = generator.generate(pair.getPrivate(), "SunRsaSign");
        var store = java.security.KeyStore.getInstance("PKCS12");
        store.load(null, null);
        char[] password = "ephemeral-test-key".toCharArray();
        store.setKeyEntry("fixture", pair.getPrivate(), password, new java.security.cert.Certificate[]{certificate});
        var km = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        km.init(store, password);
        var tm = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tm.init(store);
        var tls = javax.net.ssl.SSLContext.getInstance("TLS");
        tls.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        var https = com.sun.net.httpserver.HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        https.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(tls));
        https.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] bytes = "TLS content".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        var original = javax.net.ssl.SSLContext.getDefault();
        https.start();
        try {
            javax.net.ssl.SSLContext.setDefault(tls);
            var pinned = WebPageFetcherTestSupport.local(https, connections);
            assertThat(pinned.fetch("https://example.org/", List.of()).text()).isEqualTo("TLS content");
            assertThatThrownBy(() -> pinned.fetch("https://wrong.example/", List.of())).isInstanceOf(IOException.class);
            assertThat(requests).hasValue(1); // wrong host failed during TLS, before any GET
        } finally {
            javax.net.ssl.SSLContext.setDefault(original);
            https.stop(0);
        }
    }
}
