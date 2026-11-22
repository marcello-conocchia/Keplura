package com.keplura.server.search;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Short-lived read snapshot. Never retained across network calls, tool executions or later HTTP requests. */
public final class ReferenceReadScope {
    private record Value(Object result, RuntimeException failure) { }
    private static final ThreadLocal<Map<Object,Value>> CURRENT = new ThreadLocal<>();
    private ReferenceReadScope() { }

    public static <T> T run(Supplier<T> read) {
        if (CURRENT.get() != null) return read.get();
        CURRENT.set(new HashMap<>());
        try { return read.get(); }
        finally { CURRENT.remove(); }
    }

    @SuppressWarnings("unchecked")
    public static <T> T memo(Object key, Supplier<T> read) {
        var values = CURRENT.get();
        if (values == null) return read.get();
        var cached = values.get(key);
        if (cached == null) {
            try { cached = new Value(read.get(), null); }
            catch (RuntimeException failure) { cached = new Value(null, failure); }
            values.put(key, cached);
        }
        if (cached.failure() != null) throw cached.failure();
        return (T) cached.result();
    }

    public static void seed(Object key, Object value) {
        var values = CURRENT.get();
        if (values != null) values.putIfAbsent(key, new Value(value, null));
    }
}
