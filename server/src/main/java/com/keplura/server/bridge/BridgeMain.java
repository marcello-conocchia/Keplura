package com.keplura.server.bridge;

/** Standalone entry points without the Spring configuration or the main server's environment. */
public final class BridgeMain {
    private BridgeMain() { }

    public static int run(boolean worker) {
        try {
            if (worker) WorkerService.run(System.getenv());
            else StdioBridge.run(System.getenv());
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (Exception e) {
            System.err.println(worker ? "stdio worker service refused startup or stopped" : "stdio bridge refused startup or stopped");
            return 1;
        }
    }
}
