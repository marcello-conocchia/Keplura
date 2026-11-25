package com.keplura.server.http;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only routing of an already policy-checked public address to a local counted fixture. */
public final class WebPageFetcherTestSupport {
    public static WebPageFetcher local(HttpServer server, AtomicInteger connections) {
        return new WebPageFetcher(host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{93, (byte)184, (byte)216, 34})},
                (socket, address, port, millis) -> {
                    connections.incrementAndGet();
                    socket.connect(new InetSocketAddress("127.0.0.1", server.getAddress().getPort()), millis);
                }, Duration.ofSeconds(10));
    }
}
