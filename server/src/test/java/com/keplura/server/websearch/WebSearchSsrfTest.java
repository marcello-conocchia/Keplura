package com.keplura.server.websearch;

import com.keplura.server.admin.SsrfGuard;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class WebSearchSsrfTest {
    @Test void loopbackIsRefusedUntilExplicitOperatorApprovalAndRedirectsAreNeverFollowed() throws Exception {
        var hits = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            hits.incrementAndGet();
            assertThat(exchange.getRequestURI().getRawQuery()).contains("q=a%26b%3Fc");
            byte[] json = "{\"results\":[{\"title\":\"Fixture\",\"url\":\"https://example.test/\",\"content\":\"synthetic\"}]}".getBytes();
            exchange.sendResponseHeaders(200, json.length);
            exchange.getResponseBody().write(json); exchange.close();
        });
        server.createContext("/redirect/search", exchange -> {
            exchange.getResponseHeaders().set("Location", "/search"); exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var service = new WebSearchService("searxng", url, "", 5, "", 2);
            assertThatThrownBy(() -> service.search("a&b?c")).isInstanceOf(WebSearchService.WebSearchException.class);
            assertThat(hits.get()).isZero();
            service.setGuard(new SsrfGuard("127.0.0.1/32"));
            assertThat(service.search("a&b?c")).singleElement().satisfies(r -> assertThat(r.snippet()).isEqualTo("synthetic"));
            assertThat(hits.get()).isEqualTo(1);
            var redirect = new WebSearchService("searxng", url + "/redirect", "", 5, "", 2);
            redirect.setGuard(new SsrfGuard("127.0.0.1/32"));
            assertThatThrownBy(() -> redirect.search("a&b?c")).isInstanceOf(WebSearchService.WebSearchException.class);
            assertThat(hits.get()).isEqualTo(1);
            for (String base : new String[]{"http://user:password@127.0.0.1", url + "?target=private", url + "#fragment"}) {
                var malformed = new WebSearchService("searxng", base, "", 5, "", 2);
                malformed.setGuard(new SsrfGuard("127.0.0.1/32"));
                assertThatThrownBy(() -> malformed.search("a&b?c")).isInstanceOf(WebSearchService.WebSearchException.class);
            }
            assertThat(hits.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }
}
