package com.keplura.server.knowledge;

import com.keplura.server.search.DocumentIndexingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Background indexer (Roadmap Punkt 3, {@code keplura.indexing.mode=async}).
 * An upload commits the row + file with status PENDING and returns at once;
 * after commit the document id is queued here. A small fixed pool parses,
 * chunks and embeds in its OWN transaction; success clears the status
 * (derived INDEXED / NO_TEXT_LAYER), any failure persists FAILED. On boot,
 * every PENDING row is re-queued (crash/restart recovery).
 *
 * <p>The tenant chain is not re-checked here: only ids that the upload or
 * re-index use-case authorized are ever queued.
 */
@Component
public class DocumentIndexingQueue {

    private static final Logger log = LoggerFactory.getLogger(DocumentIndexingQueue.class);

    private final DocumentRepository documentRepository;
    private final DocumentStorage storage;
    private final DocumentIndexingService indexingService;
    private final TransactionTemplate tx;
    private final boolean async;
    private final ExecutorService pool;

    public DocumentIndexingQueue(DocumentRepository documentRepository,
                                 DocumentStorage storage,
                                 DocumentIndexingService indexingService,
                                 PlatformTransactionManager txManager,
                                 @Value("${keplura.indexing.mode:sync}") String mode,
                                 @Value("${keplura.indexing.workers:1}") int workers) {
        this.documentRepository = documentRepository;
        this.storage = storage;
        this.indexingService = indexingService;
        this.tx = new TransactionTemplate(txManager);
        String m = mode == null ? "sync" : mode.trim().toLowerCase(Locale.ROOT);
        if (!m.equals("sync") && !m.equals("async")) {
            throw new IllegalStateException("keplura.indexing.mode must be sync|async, got: " + mode);
        }
        this.async = m.equals("async");
        this.pool = async
                ? Executors.newFixedThreadPool(Math.max(1, workers), r -> {
                    Thread t = new Thread(r, "doc-indexer");
                    t.setDaemon(true);
                    return t;
                })
                : null;
        log.info("document indexing mode: {}", m);
    }

    public boolean isAsync() {
        return async;
    }

    /** Queues the document once the surrounding transaction committed. */
    public void enqueueAfterCommit(Long documentId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(documentId);
                }
            });
        } else {
            submit(documentId);
        }
    }

    private void submit(Long documentId) {
        pool.submit(() -> run(documentId));
    }

    private void run(Long documentId) {
        try {
            tx.executeWithoutResult(status -> documentRepository.findById(documentId).ifPresent(doc -> {
                indexingService.reindex(doc, storage.resolveForRead(doc.getStoredPath()));
                documentRepository.save(doc);
            }));
        } catch (RuntimeException e) {
            // class name only — parser/gateway messages can carry document text or hosts
            log.warn("indexing document {} failed ({})", documentId, e.getClass().getSimpleName());
            try {
                tx.executeWithoutResult(status -> documentRepository.findById(documentId).ifPresent(doc -> {
                    doc.markIndexFailed();
                    documentRepository.save(doc);
                }));
            } catch (RuntimeException again) {
                log.warn("could not mark document {} FAILED ({})", documentId, again.getClass().getSimpleName());
            }
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void requeuePending() {
        if (!async) {
            return;
        }
        var pending = documentRepository.findByIndexStatus(Document.INDEX_STATUS_PENDING);
        if (!pending.isEmpty()) {
            log.info("re-queueing {} pending document(s)", pending.size());
            pending.forEach(d -> submit(d.getId()));
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdown() throws InterruptedException {
        if (pool != null) {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
