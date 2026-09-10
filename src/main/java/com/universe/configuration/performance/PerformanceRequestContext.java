package com.universe.configuration.performance;

final class PerformanceRequestContext {

    private static final ThreadLocal<PerformanceRequestMetrics> CURRENT = new ThreadLocal<>();

    private PerformanceRequestContext() {
    }

    static PerformanceRequestMetrics currentOrNull() {
        return CURRENT.get();
    }

    static Scope open(PerformanceRequestMetrics metrics) {
        PerformanceRequestMetrics previous = CURRENT.get();
        CURRENT.set(metrics);
        return new Scope(previous);
    }

    static final class Scope implements AutoCloseable {

        private final PerformanceRequestMetrics previous;
        private boolean closed;

        private Scope(PerformanceRequestMetrics previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
