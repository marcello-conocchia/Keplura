package com.keplura.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class KepluraServerApplication {

    public static void main(String[] args) {
        if (args.length == 1 && ("--mcp-stdio-bridge".equals(args[0]) || "--mcp-stdio-worker-service".equals(args[0]))) {
            System.exit(com.keplura.server.bridge.BridgeMain.run("--mcp-stdio-worker-service".equals(args[0])));
        }
        SpringApplication.run(KepluraServerApplication.class, args);
    }
}
