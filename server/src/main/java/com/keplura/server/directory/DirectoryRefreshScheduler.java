package com.keplura.server.directory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** ADR-0012 §3: frischt die Verzeichnis-Gruppen aller Benutzer periodisch auf (Standard 15 min). */
@Component
public class DirectoryRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(DirectoryRefreshScheduler.class);

    private final DirectoryService directoryService;
    private final int intervalMinutes;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "directory-refresh");
        t.setDaemon(true);
        return t;
    });

    public DirectoryRefreshScheduler(DirectoryService directoryService,
                                     @Value("${keplura.directory.refresh-minutes:15}") int intervalMinutes) {
        this.directoryService = directoryService;
        this.intervalMinutes = Math.max(5, Math.min(intervalMinutes, 1440));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                int n = directoryService.refreshAllPrincipals();
                if (n > 0) log.info("directory refresh: {} user(s) updated", n);
            } catch (RuntimeException e) {
                log.warn("directory refresh failed ({})", e.getClass().getSimpleName());
            }
        }, intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }
}
