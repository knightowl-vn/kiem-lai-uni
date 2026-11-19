package com.universe.configuration.performance;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PerformanceInstrumentationTest {

    @Test
    void canonicalHomeRoutesAreInstrumented() throws Exception {
        PerformanceServerTimingFilter filter = new PerformanceServerTimingFilter();

        for (String path : List.of("/", "/home")) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (servletRequest, servletResponse) ->
                    servletResponse.getWriter().write("home body")
            );

            assertThat(response.getHeader(PerformanceServerTimingFilter.SERVER_TIMING))
                    .startsWith("total;dur=")
                    .contains("conn;dur=")
                    .contains("sql;dur=")
                    .contains("tx;dur=")
                    .contains("app;dur=");
            assertThat(response.getContentAsString()).isEqualTo("home body");
        }
    }

    @Test
    void canonicalWikiRoutesAreInstrumented() throws Exception {
        PerformanceServerTimingFilter filter = new PerformanceServerTimingFilter();

        for (String path : List.of("/wiki", "/wiki/", "/wiki/character/tran-binh-an")) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (servletRequest, servletResponse) ->
                    servletResponse.getWriter().write("wiki body")
            );

            assertThat(response.getHeader(PerformanceServerTimingFilter.SERVER_TIMING))
                    .startsWith("total;dur=")
                    .contains("conn;dur=")
                    .contains("sql;dur=")
                    .contains("tx;dur=")
                    .contains("app;dur=");
            assertThat(response.getContentAsString()).isEqualTo("wiki body");
        }
    }

    @Test
    void readerProgressAndHistoryPostsAreInstrumentedWithoutExposingRequestContent() throws Exception {
        PerformanceServerTimingFilter filter = new PerformanceServerTimingFilter();
        String sensitiveRequestBody = "{\"password\":\"must-not-appear\"}";

        for (String statePath : List.of("progress", "history")) {
            MockHttpServletRequest request = new MockHttpServletRequest(
                    "POST",
                    "/novel/chapters/00000000-0000-0000-0000-000000000001/" + statePath
            );
            request.setContent(sensitiveRequestBody.getBytes(StandardCharsets.UTF_8));
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (servletRequest, servletResponse) ->
                    ((HttpServletResponse) servletResponse).setStatus(204)
            );

            assertThat(response.getStatus()).isEqualTo(204);
            assertThat(response.getContentAsByteArray()).isEmpty();
            assertThat(response.getHeader(PerformanceServerTimingFilter.SERVER_TIMING))
                    .startsWith("total;dur=")
                    .contains("conn;dur=")
                    .contains("sql;dur=")
                    .contains("tx;dur=")
                    .contains("app;dur=")
                    .doesNotContain("password")
                    .doesNotContain("must-not-appear")
                    .doesNotContain("jdbc:");
        }
    }

    @Test
    void unrelatedPostRemainsUninstrumentedAndResponseIsUnchanged() throws Exception {
        PerformanceServerTimingFilter filter = new PerformanceServerTimingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/novel/profile");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            ((HttpServletResponse) servletResponse).setStatus(202);
            servletResponse.setContentType("text/plain");
            servletResponse.getWriter().write("unchanged unrelated response");
        });

        assertThat(response.getHeader(PerformanceServerTimingFilter.SERVER_TIMING)).isNull();
        assertThat(response.getStatus()).isEqualTo(202);
        assertThat(response.getContentType()).isEqualTo("text/plain");
        assertThat(response.getContentAsString()).isEqualTo("unchanged unrelated response");
    }

    @Test
    void staticResourceRemainsUninstrumented() throws Exception {
        PerformanceServerTimingFilter filter = new PerformanceServerTimingFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/js/theme.js");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                servletResponse.getWriter().write("static bytes")
        );

        assertThat(response.getHeader(PerformanceServerTimingFilter.SERVER_TIMING)).isNull();
        assertThat(response.getContentAsString()).isEqualTo("static bytes");
    }

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
    void voiceCatalogAndChapterPlaybackAreBothMeasuredForFocusedComparison() throws Exception {
        PerformanceServerTimingFilter filter = new PerformanceServerTimingFilter(
                new SequenceNanoTime(0, 4_000_000, 10_000_000, 19_000_000)
        );
        MockHttpServletResponse catalogResponse = new MockHttpServletResponse();
        MockHttpServletResponse playbackResponse = new MockHttpServletResponse();

        filter.doFilter(
                new MockHttpServletRequest("GET", "/api/novel/narration/voices"),
                catalogResponse,
                (request, response) -> response.getWriter().write("{\"voices\":[]}")
        );
        filter.doFilter(
                new MockHttpServletRequest(
                        "GET",
                        "/api/novel/chapters/00000000-0000-0000-0000-000000000001/narration/playback"
                ),
                playbackResponse,
                (request, response) -> response.getWriter().write("{\"playback\":null}")
        );

        assertThat(catalogResponse.getHeader(PerformanceServerTimingFilter.SERVER_TIMING))
                .startsWith("total;dur=4.000");
        assertThat(playbackResponse.getHeader(PerformanceServerTimingFilter.SERVER_TIMING))
                .startsWith("total;dur=9.000");
    }

    @Test
    void screenDiscussionFeedGetsAreInstrumentedWithoutExposingUuidOrQueryParams() throws Exception {
        PerformanceServerTimingFilter filter = new PerformanceServerTimingFilter(
                new SequenceNanoTime(0, 12_000_000, 20_000_000, 35_000_000)
        );
        Logger filterLogger = (Logger) LoggerFactory.getLogger(PerformanceServerTimingFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        filterLogger.addAppender(appender);
        try {
            String titleUuid = "11111111-1111-1111-1111-111111111111";

            MockHttpServletRequest commentsRequest = new MockHttpServletRequest(
                    "GET",
                    "/api/screen/titles/" + titleUuid + "/comments"
            );
            commentsRequest.setQueryString("page=0&size=20&sort=NEWEST&secret=must-not-appear");
            MockHttpServletResponse commentsResponse = new MockHttpServletResponse();

            filter.doFilter(commentsRequest, commentsResponse, (req, res) -> {
                PerformanceRequestMetrics metrics = PerformanceRequestContext.currentOrNull();
                assertThat(metrics).isNotNull();
                metrics.recordConnectionAcquisition(1_000_000L);
                metrics.recordSqlExecution(4_000_000L);
                metrics.recordTransactionControl("setAutoCommit", 2_000_000L);
                res.setContentType("application/json");
                res.getWriter().write("{\"threads\":[]}");
            });

            assertThat(commentsResponse.getContentAsString()).isEqualTo("{\"threads\":[]}");
            assertThat(commentsResponse.getContentType()).isEqualTo("application/json");
            assertThat(commentsResponse.getHeader(PerformanceServerTimingFilter.SERVER_TIMING))
                    .startsWith("total;dur=12.000")
                    .contains("conn;dur=1.000;desc=\"1 acquisitions\"")
                    .contains("sql;dur=4.000;desc=\"1 statements\"")
                    .contains("tx;dur=2.000;desc=\"1 control calls\"")
                    .contains("tx_ac;dur=2.000;desc=\"1 calls max=2.0ms\"")
                    .contains("app;dur=5.000")
                    .doesNotContain(titleUuid)
                    .doesNotContain("must-not-appear");

            MockHttpServletRequest feedAliasRequest = new MockHttpServletRequest(
                    "GET",
                    "/api/screen/titles/" + titleUuid + "/comments/feed"
            );
            MockHttpServletResponse feedAliasResponse = new MockHttpServletResponse();

            filter.doFilter(feedAliasRequest, feedAliasResponse, (req, res) -> {
                res.setContentType("application/json");
                res.getWriter().write("{\"threads\":[],\"alias\":true}");
            });

            assertThat(feedAliasResponse.getContentAsString()).isEqualTo("{\"threads\":[],\"alias\":true}");
            assertThat(feedAliasResponse.getHeader(PerformanceServerTimingFilter.SERVER_TIMING))
                    .startsWith("total;dur=15.000")
                    .doesNotContain(titleUuid);

            List<String> diagnosticLogs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(msg -> msg.contains("[PERF-DIAGNOSTIC]"))
                    .toList();
            assertThat(diagnosticLogs).hasSize(2);
            assertThat(diagnosticLogs.get(0))
                    .contains("/api/screen/titles/{titleId}/comments request: total=12.00ms")
                    .doesNotContain(titleUuid)
                    .doesNotContain("must-not-appear");
            assertThat(diagnosticLogs.get(1))
                    .contains("/api/screen/titles/{titleId}/comments/feed request: total=15.00ms")
                    .doesNotContain(titleUuid);
        } finally {
            filterLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void screenThreadRevisionsMutationsAndHeadRemainUninstrumented() throws Exception {
        PerformanceServerTimingFilter filter = new PerformanceServerTimingFilter();
        String titleUuid = "11111111-1111-1111-1111-111111111111";
        String commentUuid = "22222222-2222-2222-2222-222222222222";

        List<MockHttpServletRequest> requests = List.of(
                new MockHttpServletRequest("HEAD", "/api/screen/titles/" + titleUuid + "/comments"),
                new MockHttpServletRequest("POST", "/api/screen/titles/" + titleUuid + "/comments"),
                new MockHttpServletRequest("GET", "/api/screen/titles/" + titleUuid + "/comments/" + commentUuid + "/thread"),
                new MockHttpServletRequest("GET", "/api/screen/titles/" + titleUuid + "/comments/" + commentUuid + "/revisions"),
                new MockHttpServletRequest("GET", "/api/screen/titles//comments")
        );

        for (MockHttpServletRequest request : requests) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {
                ((HttpServletResponse) res).setStatus(200);
                res.getWriter().write("ok");
            });

            assertThat(response.getHeader(PerformanceServerTimingFilter.SERVER_TIMING)).isNull();
            assertThat(response.getContentAsString()).isEqualTo("ok");
        }
    }

    @Test
    void allFiveTransactionControlMethodsAreMeasuredSeparately() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(delegate.getConnection()).thenReturn(connection);

        PerformanceDataSource dataSource = new PerformanceDataSource(
                delegate,
                new SequenceNanoTime(
                        0, 1_000_000,         // getConnection (1ms)
                        1_000_000, 3_000_000, // setAutoCommit (2ms)
                        3_000_000, 6_000_000, // setReadOnly (3ms)
                        6_000_000, 10_000_000, // setTransactionIsolation (4ms)
                        10_000_000, 15_000_000, // commit (5ms)
                        15_000_000, 21_000_000  // rollback (6ms)
                )
        );
        PerformanceRequestMetrics metrics = new PerformanceRequestMetrics();

        try (PerformanceRequestContext.Scope ignored = PerformanceRequestContext.open(metrics);
             Connection measured = dataSource.getConnection()) {
            measured.setAutoCommit(false);
            measured.setReadOnly(true);
            measured.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            measured.commit();
            measured.rollback();
        }

        PerformanceRequestMetrics.Snapshot snapshot = metrics.snapshot();
        assertThat(snapshot.connectionAcquisitionNanos()).isEqualTo(1_000_000);
        assertThat(snapshot.transactionControlCount()).isEqualTo(5);
        assertThat(snapshot.transactionControlNanos()).isEqualTo(20_000_000);

        assertThat(snapshot.setAutoCommit().count()).isEqualTo(1);
        assertThat(snapshot.setAutoCommit().durationNanos()).isEqualTo(2_000_000);
        assertThat(snapshot.setAutoCommit().maxDurationNanos()).isEqualTo(2_000_000);

        assertThat(snapshot.setReadOnly().count()).isEqualTo(1);
        assertThat(snapshot.setReadOnly().durationNanos()).isEqualTo(3_000_000);
        assertThat(snapshot.setReadOnly().maxDurationNanos()).isEqualTo(3_000_000);

        assertThat(snapshot.setTransactionIsolation().count()).isEqualTo(1);
        assertThat(snapshot.setTransactionIsolation().durationNanos()).isEqualTo(4_000_000);
        assertThat(snapshot.setTransactionIsolation().maxDurationNanos()).isEqualTo(4_000_000);

        assertThat(snapshot.commit().count()).isEqualTo(1);
        assertThat(snapshot.commit().durationNanos()).isEqualTo(5_000_000);
        assertThat(snapshot.commit().maxDurationNanos()).isEqualTo(5_000_000);

        assertThat(snapshot.rollback().count()).isEqualTo(1);
        assertThat(snapshot.rollback().durationNanos()).isEqualTo(6_000_000);
        assertThat(snapshot.rollback().maxDurationNanos()).isEqualTo(6_000_000);
    }

    @Test
    void multipleCallsToSameMethodAccumulateAndTrackMax() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(delegate.getConnection()).thenReturn(connection);

        PerformanceDataSource dataSource = new PerformanceDataSource(
                delegate,
                new SequenceNanoTime(
                        0, 1_000_000,          // getConnection (1ms)
                        1_000_000, 3_000_000,  // setAutoCommit #1 (2ms)
                        3_000_000, 11_000_000, // setAutoCommit #2 (8ms - max)
                        11_000_000, 14_000_000 // setAutoCommit #3 (3ms)
                )
        );
        PerformanceRequestMetrics metrics = new PerformanceRequestMetrics();

        try (PerformanceRequestContext.Scope ignored = PerformanceRequestContext.open(metrics);
             Connection measured = dataSource.getConnection()) {
            measured.setAutoCommit(false);
            measured.setAutoCommit(true);
            measured.setAutoCommit(false);
        }

        PerformanceRequestMetrics.Snapshot snapshot = metrics.snapshot();
        assertThat(snapshot.setAutoCommit().count()).isEqualTo(3);
        assertThat(snapshot.setAutoCommit().durationNanos()).isEqualTo(13_000_000);
        assertThat(snapshot.setAutoCommit().maxDurationNanos()).isEqualTo(8_000_000);
        assertThat(snapshot.transactionControlCount()).isEqualTo(3);
        assertThat(snapshot.transactionControlNanos()).isEqualTo(13_000_000);
    }

    @Test
    void exceptionsInTransactionMethodsAreRecordedAndRethrown() throws Exception {
        DataSource delegate = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        doThrow(new SQLException("Aiven simulated network failure")).when(connection).commit();
        when(delegate.getConnection()).thenReturn(connection);

        PerformanceDataSource dataSource = new PerformanceDataSource(
                delegate,
                new SequenceNanoTime(
                        0, 1_000_000,         // getConnection (1ms)
                        1_000_000, 5_000_000  // commit failure (4ms)
                )
        );
        PerformanceRequestMetrics metrics = new PerformanceRequestMetrics();

        try (PerformanceRequestContext.Scope ignored = PerformanceRequestContext.open(metrics);
             Connection measured = dataSource.getConnection()) {
            assertThatThrownBy(measured::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Aiven simulated network failure");
        }

        PerformanceRequestMetrics.Snapshot snapshot = metrics.snapshot();
        assertThat(snapshot.commit().count()).isEqualTo(1);
        assertThat(snapshot.commit().durationNanos()).isEqualTo(4_000_000);
        assertThat(snapshot.commit().maxDurationNanos()).isEqualTo(4_000_000);
        assertThat(snapshot.transactionControlCount()).isEqualTo(1);
        assertThat(snapshot.transactionControlNanos()).isEqualTo(4_000_000);
    }

    @Test
    void serverTimingIncludesMethodBreakdownAndViewMetricsWhenPresent() throws Exception {
        PerformanceServerTimingFilter filter = new PerformanceServerTimingFilter(
                new SequenceNanoTime(0, 30_000_000)
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/wiki");
        request.setAttribute(PerformanceViewTimingInterceptor.ATTR_HANDLER_DURATION, 15_000_000L);
        request.setAttribute(PerformanceViewTimingInterceptor.ATTR_VIEW_DURATION, 5_000_000L);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> {
            PerformanceRequestMetrics metrics = PerformanceRequestContext.currentOrNull();
            assertThat(metrics).isNotNull();
            metrics.recordConnectionAcquisition(2_000_000L);
            metrics.recordSqlExecution(4_000_000L);
            metrics.recordTransactionControl("setAutoCommit", 3_000_000L);
            metrics.recordTransactionControl("commit", 2_000_000L);
            servletResponse.getWriter().write("wiki response");
        });

        String header = response.getHeader(PerformanceServerTimingFilter.SERVER_TIMING);
        assertThat(header).isNotNull();
        assertThat(header).startsWith("total;dur=30.000");
        assertThat(header).contains("conn;dur=2.000;desc=\"1 acquisitions\"");
        assertThat(header).contains("sql;dur=4.000;desc=\"1 statements\"");
        assertThat(header).contains("tx;dur=5.000;desc=\"2 control calls\"");
        assertThat(header).contains("ctrl;dur=15.000");
        assertThat(header).contains("view_est;dur=5.000");
        assertThat(header).contains("tx_ac;dur=3.000;desc=\"1 calls max=3.0ms\"");
        assertThat(header).contains("tx_cm;dur=2.000;desc=\"1 calls max=2.0ms\"");
        assertThat(header).doesNotContain("tx_rb");
        assertThat(header).doesNotContain("tx_ro");
    }

    @Test
    void interceptorRecordsHandlerAndViewDurationsAccurately() throws Exception {
        PerformanceViewTimingInterceptor interceptor = new PerformanceViewTimingInterceptor(
                new SequenceNanoTime(
                        1_000_000,  // preHandle start (1ms)
                        10_000_000, // postHandle end / view start (10ms)
                        16_000_000  // afterCompletion view end (16ms)
                )
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/wiki");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean proceed = interceptor.preHandle(request, response, new Object());
        assertThat(proceed).isTrue();

        interceptor.postHandle(request, response, new Object(), null);
        assertThat(request.getAttribute(PerformanceViewTimingInterceptor.ATTR_HANDLER_DURATION))
                .isEqualTo(9_000_000L); // 10ms - 1ms = 9ms

        interceptor.afterCompletion(request, response, new Object(), null);
        assertThat(request.getAttribute(PerformanceViewTimingInterceptor.ATTR_VIEW_DURATION))
                .isEqualTo(6_000_000L); // 16ms - 10ms = 6ms
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
