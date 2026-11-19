package com.universe.configuration.performance;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

final class PerformanceRequestMetrics {

    private final LongAdder connectionAcquisitionCount = new LongAdder();
    private final LongAdder connectionAcquisitionNanos = new LongAdder();
    private final LongAdder sqlStatementCount = new LongAdder();
    private final LongAdder sqlExecutionNanos = new LongAdder();
    private final LongAdder transactionControlCount = new LongAdder();
    private final LongAdder transactionControlNanos = new LongAdder();

    private final MethodMetrics setAutoCommitMetrics = new MethodMetrics();
    private final MethodMetrics setReadOnlyMetrics = new MethodMetrics();
    private final MethodMetrics setTransactionIsolationMetrics = new MethodMetrics();
    private final MethodMetrics commitMetrics = new MethodMetrics();
    private final MethodMetrics rollbackMetrics = new MethodMetrics();
    private final MethodMetrics otherTxMetrics = new MethodMetrics();

    void recordConnectionAcquisition(long durationNanos) {
        connectionAcquisitionCount.increment();
        connectionAcquisitionNanos.add(nonNegative(durationNanos));
    }

    void recordSqlExecution(long durationNanos) {
        sqlStatementCount.increment();
        sqlExecutionNanos.add(nonNegative(durationNanos));
    }

    void recordTransactionControl(long durationNanos) {
        recordTransactionControl("other", durationNanos);
    }

    void recordTransactionControl(String methodName, long durationNanos) {
        long duration = nonNegative(durationNanos);
        transactionControlCount.increment();
        transactionControlNanos.add(duration);
        if (methodName != null) {
            switch (methodName) {
                case "setAutoCommit" -> setAutoCommitMetrics.record(duration);
                case "setReadOnly" -> setReadOnlyMetrics.record(duration);
                case "setTransactionIsolation" -> setTransactionIsolationMetrics.record(duration);
                case "commit" -> commitMetrics.record(duration);
                case "rollback" -> rollbackMetrics.record(duration);
                default -> otherTxMetrics.record(duration);
            }
        } else {
            otherTxMetrics.record(duration);
        }
    }

    Snapshot snapshot() {
        return new Snapshot(
                connectionAcquisitionCount.sum(),
                connectionAcquisitionNanos.sum(),
                sqlStatementCount.sum(),
                sqlExecutionNanos.sum(),
                transactionControlCount.sum(),
                transactionControlNanos.sum(),
                setAutoCommitMetrics.snapshot(),
                setReadOnlyMetrics.snapshot(),
                setTransactionIsolationMetrics.snapshot(),
                commitMetrics.snapshot(),
                rollbackMetrics.snapshot()
        );
    }

    private long nonNegative(long durationNanos) {
        return Math.max(0, durationNanos);
    }

    static final class MethodMetrics {
        private final LongAdder count = new LongAdder();
        private final LongAdder durationNanos = new LongAdder();
        private final AtomicLong maxDurationNanos = new AtomicLong();

        void record(long duration) {
            count.increment();
            durationNanos.add(duration);
            maxDurationNanos.accumulateAndGet(duration, Math::max);
        }

        MethodSnapshot snapshot() {
            return new MethodSnapshot(count.sum(), durationNanos.sum(), maxDurationNanos.get());
        }
    }

    record MethodSnapshot(long count, long durationNanos, long maxDurationNanos) {
        public double durationMs() {
            return durationNanos / 1_000_000.0;
        }

        public double maxDurationMs() {
            return maxDurationNanos / 1_000_000.0;
        }
    }

    record Snapshot(
            long connectionAcquisitionCount,
            long connectionAcquisitionNanos,
            long sqlStatementCount,
            long sqlExecutionNanos,
            long transactionControlCount,
            long transactionControlNanos,
            MethodSnapshot setAutoCommit,
            MethodSnapshot setReadOnly,
            MethodSnapshot setTransactionIsolation,
            MethodSnapshot commit,
            MethodSnapshot rollback
    ) {
        public Snapshot(
                long connectionAcquisitionCount,
                long connectionAcquisitionNanos,
                long sqlStatementCount,
                long sqlExecutionNanos,
                long transactionControlCount,
                long transactionControlNanos
        ) {
            this(
                    connectionAcquisitionCount,
                    connectionAcquisitionNanos,
                    sqlStatementCount,
                    sqlExecutionNanos,
                    transactionControlCount,
                    transactionControlNanos,
                    new MethodSnapshot(0, 0, 0),
                    new MethodSnapshot(0, 0, 0),
                    new MethodSnapshot(0, 0, 0),
                    new MethodSnapshot(0, 0, 0),
                    new MethodSnapshot(0, 0, 0)
            );
        }
    }
}
