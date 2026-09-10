package com.universe.configuration.performance;

import java.util.concurrent.atomic.LongAdder;

final class PerformanceRequestMetrics {

    private final LongAdder connectionAcquisitionCount = new LongAdder();
    private final LongAdder connectionAcquisitionNanos = new LongAdder();
    private final LongAdder sqlStatementCount = new LongAdder();
    private final LongAdder sqlExecutionNanos = new LongAdder();
    private final LongAdder transactionControlCount = new LongAdder();
    private final LongAdder transactionControlNanos = new LongAdder();

    void recordConnectionAcquisition(long durationNanos) {
        connectionAcquisitionCount.increment();
        connectionAcquisitionNanos.add(nonNegative(durationNanos));
    }

    void recordSqlExecution(long durationNanos) {
        sqlStatementCount.increment();
        sqlExecutionNanos.add(nonNegative(durationNanos));
    }

    void recordTransactionControl(long durationNanos) {
        transactionControlCount.increment();
        transactionControlNanos.add(nonNegative(durationNanos));
    }

    Snapshot snapshot() {
        return new Snapshot(
                connectionAcquisitionCount.sum(),
                connectionAcquisitionNanos.sum(),
                sqlStatementCount.sum(),
                sqlExecutionNanos.sum(),
                transactionControlCount.sum(),
                transactionControlNanos.sum()
        );
    }

    private long nonNegative(long durationNanos) {
        return Math.max(0, durationNanos);
    }

    record Snapshot(
            long connectionAcquisitionCount,
            long connectionAcquisitionNanos,
            long sqlStatementCount,
            long sqlExecutionNanos,
            long transactionControlCount,
            long transactionControlNanos
    ) {
    }
}
