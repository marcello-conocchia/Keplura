package com.keplura.server.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * TU-04c review: durable cleanup of private tool arguments whose normal state change never happened (process
 * ended while a call was RUNNING, proposal never answered). Runs at start and every minute. Only clears
 * arguments and persists expiry; it never executes, retries or resolves an unclear call.
 */
@Component
public class ToolCallSweeper {
    private static final Logger log = LoggerFactory.getLogger(ToolCallSweeper.class);
    private final ToolCallStore cards;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "tool-call-sweeper");
        t.setDaemon(true);
        return t;
    });

    public ToolCallSweeper(ToolCallStore cards) { this.cards = cards; }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        scheduler.scheduleWithFixedDelay(this::sweep, 0, 1, TimeUnit.MINUTES);
    }

    void sweep() {
        try {
            int n = cards.sweepPrivate(null, Instant.now());
            if (n > 0) log.info("tool call sweep: {} card(s) cleaned", n);
        } catch (RuntimeException e) {
            log.warn("tool call sweep failed ({})", e.getClass().getSimpleName());
        }
    }

    @jakarta.annotation.PreDestroy
    void stop() { scheduler.shutdownNow(); }
}
