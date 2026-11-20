package com.keplura.server.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounds a streaming response before allocating or parsing its complete body. */
public final class BoundedHttpBody extends InputStream {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "bounded-http-body");
        thread.setDaemon(true);
        return thread;
    });
    private final InputStream input;
    private final int maximum;
    private int received;
    private final AtomicBoolean expired = new AtomicBoolean();
    private final ScheduledFuture<?> timeout;

    public BoundedHttpBody(InputStream input, int maximum, Duration deadline) {
        if (maximum < 1) throw new IllegalArgumentException("positive body limit required");
        this.input = input;
        this.maximum = maximum;
        this.timeout = TIMER.schedule(() -> {
            expired.set(true);
            try { input.close(); } catch (IOException ignored) { }
        }, Math.max(1, deadline.toMillis()), TimeUnit.MILLISECONDS);
    }

    private void check(int count) throws IOException {
        if (expired.get()) throw new HttpTimeoutException("Antwortzeit überschritten.");
        if (count > 0) received += count;
        if (received > maximum) throw new IOException("Antwort zu groß.");
    }

    @Override public int read() throws IOException {
        check(0);
        int value = input.read();
        check(value < 0 ? 0 : 1);
        return value;
    }

    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        check(0);
        int count = input.read(bytes, offset, Math.min(length, maximum - received + 1));
        check(count);
        return count;
    }

    @Override public void close() throws IOException {
        timeout.cancel(false);
        input.close();
    }
}
