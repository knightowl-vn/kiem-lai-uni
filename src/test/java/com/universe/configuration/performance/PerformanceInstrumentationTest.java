package com.universe.configuration.performance;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PerformanceInstrumentationTest {

    @Test
    void enabledFilterEmitsRequestScopedMetricsWithoutChangingTheResponse() throws Exception {
        PerformanceServerTimingFilter filter = new PerformanceServerTimingFilter(
                new SequenceNanoTime(0, 20_000_000)
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/novel");
        request.setQueryString("voiceKey=must-not-appear");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            PerformanceRequestMetrics metrics = PerformanceRequestContext.currentOrNull();
            assertThat(metrics).isNotNull();
            metrics.recordConnectionAcquisition(2_000_000);
            metrics.recordSqlExecution(5_000_000);
            metrics.recordTransactionControl(1_000_000);
            servletResponse.setContentType("text/plain");
            servletResponse.getWriter().write("unchanged body");
        });

        assertThat(response.getContentAsString()).isEqualTo("unchanged body");
        assertThat(response.getContentType()).isEqualTo("text/plain");
        assertThat(response.getHeader(PerformanceServerTimingFilter.SERVER_TIMING))
                .isEqualTo(
                        "total;dur=20.000, conn;dur=2.000;desc=\"1 acquisitions\", "
                                + "sql;dur=5.000;desc=\"1 statements\", "
                                + "tx;dur=1.000;desc=\"1 control calls\", app;dur=12.000"
                )
                .doesNotContain("must-not-appear")
                .doesNotContain("password")
                .doesNotContain("jdbc:");
        assertThat(PerformanceRequestContext.currentOrNull()).isNull();
    }

    @Test
    void connectionAcquisitionDurationIsAccumulated() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(delegate.getConnection()).thenReturn(connection);
        PerformanceDataSource dataSource = new PerformanceDataSource(
                delegate,
                new SequenceNanoTime(0, 3_000_000, 3_000_000, 8_000_000)
        );
        PerformanceRequestMetrics metrics = new PerformanceRequestMetrics();

        try (PerformanceRequestContext.Scope ignored = PerformanceRequestContext.open(metrics)) {
            dataSource.getConnection();
            dataSource.getConnection();
        }

        PerformanceRequestMetrics.Snapshot snapshot = metrics.snapshot();
        assertThat(snapshot.connectionAcquisitionCount()).isEqualTo(2);
        assertThat(snapshot.connectionAcquisitionNanos()).isEqualTo(8_000_000);
    }

    @Test
    void sqlExecutionAndTransactionControlAreMeasuredSeparately() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.execute()).thenReturn(true);
        PerformanceDataSource dataSource = new PerformanceDataSource(
                delegate,
                new SequenceNanoTime(
                        0, 2_000_000,
                        2_000_000, 5_000_000,
                        5_000_000, 12_000_000
                )
        );
        PerformanceRequestMetrics metrics = new PerformanceRequestMetrics();

        try (PerformanceRequestContext.Scope ignored = PerformanceRequestContext.open(metrics);
             Connection measuredConnection = dataSource.getConnection()) {
            measuredConnection.setAutoCommit(false);
            try (PreparedStatement measuredStatement = measuredConnection.prepareStatement("select 1")) {
                measuredStatement.execute();
            }
        }

        PerformanceRequestMetrics.Snapshot snapshot = metrics.snapshot();
        assertThat(snapshot.connectionAcquisitionNanos()).isEqualTo(2_000_000);
        assertThat(snapshot.transactionControlCount()).isEqualTo(1);
        assertThat(snapshot.transactionControlNanos()).isEqualTo(3_000_000);
        assertThat(snapshot.sqlStatementCount()).isEqualTo(1);
        assertThat(snapshot.sqlExecutionNanos()).isEqualTo(7_000_000);
    }

    @Test
    void sqlCountsRemainIsolatedAcrossConcurrentRequests() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(delegate.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.execute()).thenReturn(true);
        PerformanceDataSource dataSource = new PerformanceDataSource(delegate);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<PerformanceRequestMetrics.Snapshot>> results = executor.invokeAll(List.of(
                    measuredExecutions(dataSource, 2),
                    measuredExecutions(dataSource, 5)
            ));

            assertThat(results.get(0).get().sqlStatementCount()).isEqualTo(2);
            assertThat(results.get(1).get().sqlStatementCount()).isEqualTo(5);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void mediaContentPublishesFirstByteTimingWithoutChangingBytes() throws Exception {
        PerformanceServerTimingFilter filter = new PerformanceServerTimingFilter(
                new SequenceNanoTime(0, 10_000_000)
        );
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/media/assets/00000000-0000-0000-0000-000000000001/content"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        byte[] expected = "audio bytes".getBytes(StandardCharsets.UTF_8);

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                servletResponse.getOutputStream().write(expected)
        );

        assertThat(response.getContentAsByteArray()).isEqualTo(expected);
        assertThat(response.getHeader(PerformanceServerTimingFilter.SERVER_TIMING))
                .startsWith("firstbyte;dur=10.000")
                .contains("sql;dur=0.000;desc=\"0 statements\"");
    }

    @Test
    void voiceCatalogAndLegacyManifestAreBothMeasuredForFocusedComparison() throws Exception {
        PerformanceServerTimingFilter filter = new PerformanceServerTimingFilter(
                new SequenceNanoTime(0, 4_000_000, 10_000_000, 19_000_000)
        );
        MockHttpServletResponse catalogResponse = new MockHttpServletResponse();
        MockHttpServletResponse manifestResponse = new MockHttpServletResponse();

        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/novel/narration/voices"),
                catalogResponse,
                (request, response) -> response.getWriter().write("{\"voices\":[]}")
        );
        filter.doFilter(
                new MockHttpServletRequest(
                        "GET",
                        "/api/novel/chapters/00000000-0000-0000-0000-000000000001/narration/manifest"
                ),
                manifestResponse,
                (request, response) -> response.getWriter().write("{\"segments\":[]}")
        );

        assertThat(catalogResponse.getHeader(PerformanceServerTimingFilter.SERVER_TIMING))
                .startsWith("total;dur=4.000");
        assertThat(manifestResponse.getHeader(PerformanceServerTimingFilter.SERVER_TIMING))
                .startsWith("total;dur=9.000");
    }

    private Callable<PerformanceRequestMetrics.Snapshot> measuredExecutions(
            PerformanceDataSource dataSource,
            int count
    ) {
        return () -> {
            PerformanceRequestMetrics metrics = new PerformanceRequestMetrics();
            try (PerformanceRequestContext.Scope ignored = PerformanceRequestContext.open(metrics)) {
                for (int index = 0; index < count; index++) {
                    try (Connection connection = dataSource.getConnection();
                         PreparedStatement statement = connection.prepareStatement(
                                 "select value from table where secret = ?"
                         )) {
                        statement.execute();
                    }
                }
            }
            return metrics.snapshot();
        };
    }

    private static final class SequenceNanoTime implements LongSupplier {

        private final long[] values;
        private final AtomicInteger index = new AtomicInteger();

        private SequenceNanoTime(long... values) {
            this.values = values;
        }

        @Override
        public long getAsLong() {
            int current = index.getAndIncrement();
            if (current >= values.length) {
                throw new AssertionError("No nanoTime value configured for call " + current);
            }
            return values[current];
        }
    }
}
