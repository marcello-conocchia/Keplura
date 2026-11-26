package com.keplura.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class KepluraServerApplication {

    public static void main(String[] args) {
        // TU-06c: the stdio MCP bridge is a separate mode for its own container. It never boots Spring, opens no
        // database and reads no Keplura secret; the normal server never starts stdio processes.
        if (com.keplura.server.bridge.McpStdioBridge.requested(args)) {
            System.exit(com.keplura.server.bridge.McpStdioBridge.run(args, System.getenv(),
                    com.keplura.server.bridge.McpStdioBridge::currentUid, System.err));
            return;
        }
        SpringApplication.run(KepluraServerApplication.class, args);
    }
}
