package com.universe.configuration.performance;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FilterWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

final class PerformanceServerTimingFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(PerformanceServerTimingFilter.class);

    static final String SERVER_TIMING = "Server-Timing";

    private final LongSupplier nanoTime;

    PerformanceServerTimingFilter() {
        this(System::nanoTime);
    }

    PerformanceServerTimingFilter(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long started = nanoTime.getAsLong();
        PerformanceRequestMetrics metrics = new PerformanceRequestMetrics();

        try (PerformanceRequestContext.Scope ignored = PerformanceRequestContext.open(metrics)) {
            if (isMediaContentRequest(request)) {
                filterStreamingResponse(request, response, filterChain, metrics, started);
            } else {
                filterBufferedResponse(request, response, filterChain, metrics, started);
            }
        }
    }

    private void filterBufferedResponse(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain,
            PerformanceRequestMetrics metrics,
            long started
    ) throws ServletException, IOException {
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        boolean completed = false;
        try {
            filterChain.doFilter(request, wrapped);
            completed = true;
        } finally {
            if (completed) {
                long elapsed = nanoTime.getAsLong() - started;
                PerformanceRequestMetrics.Snapshot snapshot = metrics.snapshot();
                wrapped.setHeader(SERVER_TIMING, headerValue("total", elapsed, snapshot, request));
                logDiagnostics(request, elapsed, snapshot);
                wrapped.copyBodyToResponse();
            }
        }
    }

    private void filterStreamingResponse(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain,
            PerformanceRequestMetrics metrics,
            long started
    ) throws ServletException, IOException {
        FirstByteTimingResponseWrapper wrapped = new FirstByteTimingResponseWrapper(
                response,
                metrics,
                started,
                nanoTime,
                request
        );
        filterChain.doFilter(request, wrapped);
        if (!request.isAsyncStarted()) {
            wrapped.publish("total");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI().substring(request.getContextPath().length());

        if ("POST".equals(method)) {
            return !isReaderStateWritePath(path);
        }
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            return true;
        }

        return !("GET".equals(method) && (path.equals("/")
                || path.equals("/home")
                || isScreenDiscussionFeedPath(path))
                || path.equals("/novel")
                || path.startsWith("/novel/")
                || path.equals("/wiki")
                || path.startsWith("/wiki/")
                || path.equals("/api/novel/narration/voices")
                || isPlaybackMetadataPath(path)
                || isMediaContentPath(path));
    }

    private boolean isReaderStateWritePath(String path) {
        String prefix = "/novel/chapters/";
        if (!path.startsWith(prefix)) {
            return false;
        }

        int stateSeparator = path.indexOf('/', prefix.length());
        if (stateSeparator < 0 || stateSeparator == prefix.length()) {
            return false;
        }

        String statePath = path.substring(stateSeparator);
        return statePath.equals("/progress") || statePath.equals("/history");
    }

    private boolean isMediaContentRequest(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return isMediaContentPath(path);
    }

    private boolean isPlaybackMetadataPath(String path) {
        return path.startsWith("/api/novel/chapters/")
                && path.endsWith("/narration/playback");
    }

    private boolean isMediaContentPath(String path) {
        return path.startsWith("/media/assets/") && path.endsWith("/content");
    }

    private static boolean isScreenDiscussionFeedPath(String path) {
        String prefix = "/api/screen/titles/";
        if (!path.startsWith(prefix)) {
            return false;
        }
        int nextSlash = path.indexOf('/', prefix.length());
        if (nextSlash <= prefix.length()) {
            return false;
        }
        String suffix = path.substring(nextSlash);
        return suffix.equals("/comments") || suffix.equals("/comments/feed");
    }

    private static String diagnosticPath(HttpServletRequest request) {
        String contextPath = request.getContextPath() != null ? request.getContextPath() : "";
        String uri = request.getRequestURI();
        String path = uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : uri;
        if (isScreenDiscussionFeedPath(path)) {
            String suffix = path.endsWith("/comments/feed") ? "/comments/feed" : "/comments";
            return contextPath + "/api/screen/titles/{titleId}" + suffix;
        }
        return uri;
    }

    static String headerValue(
            String primaryMetric,
            long elapsedNanos,
            PerformanceRequestMetrics.Snapshot snapshot
    ) {
        return headerValue(primaryMetric, elapsedNanos, snapshot, null);
    }

    static String headerValue(
            String primaryMetric,
            long elapsedNanos,
            PerformanceRequestMetrics.Snapshot snapshot,
            HttpServletRequest request
    ) {
        long measuredNanos = snapshot.connectionAcquisitionNanos()
                + snapshot.sqlExecutionNanos()
                + snapshot.transactionControlNanos();
        long applicationNanos = Math.max(0, elapsedNanos - measuredNanos);

        StringBuilder sb = new StringBuilder(128);
        sb.append(String.format(
                Locale.ROOT,
                "%s;dur=%.3f, conn;dur=%.3f;desc=\"%d acquisitions\", "
                        + "sql;dur=%.3f;desc=\"%d statements\", "
                        + "tx;dur=%.3f;desc=\"%d control calls\", app;dur=%.3f",
                primaryMetric,
                milliseconds(elapsedNanos),
                milliseconds(snapshot.connectionAcquisitionNanos()),
                snapshot.connectionAcquisitionCount(),
                milliseconds(snapshot.sqlExecutionNanos()),
                snapshot.sqlStatementCount(),
                milliseconds(snapshot.transactionControlNanos()),
                snapshot.transactionControlCount(),
                milliseconds(applicationNanos)
        ));

        if (request != null) {
            Object handlerDurObj = request.getAttribute(PerformanceViewTimingInterceptor.ATTR_HANDLER_DURATION);
            Object viewDurObj = request.getAttribute(PerformanceViewTimingInterceptor.ATTR_VIEW_DURATION);
            if (handlerDurObj instanceof Long hDur) {
                sb.append(String.format(Locale.ROOT, ", ctrl;dur=%.3f", milliseconds(hDur)));
            }
            if (viewDurObj instanceof Long vDur) {
                sb.append(String.format(Locale.ROOT, ", view_est;dur=%.3f", milliseconds(vDur)));
            }
        }

        appendMethodTiming(sb, "tx_ac", snapshot.setAutoCommit());
        appendMethodTiming(sb, "tx_ro", snapshot.setReadOnly());
        appendMethodTiming(sb, "tx_iso", snapshot.setTransactionIsolation());
        appendMethodTiming(sb, "tx_cm", snapshot.commit());
        appendMethodTiming(sb, "tx_rb", snapshot.rollback());

        return sb.toString();
    }

    private static void appendMethodTiming(
            StringBuilder sb,
            String metricName,
            PerformanceRequestMetrics.MethodSnapshot methodSnapshot
    ) {
        if (methodSnapshot != null && methodSnapshot.count() > 0) {
            sb.append(String.format(
                    Locale.ROOT,
                    ", %s;dur=%.3f;desc=\"%d calls max=%.1fms\"",
                    metricName,
                    milliseconds(methodSnapshot.durationNanos()),
                    methodSnapshot.count(),
                    milliseconds(methodSnapshot.maxDurationNanos())
            ));
        }
    }

    private static void logDiagnostics(
            HttpServletRequest request,
            long elapsedNanos,
            PerformanceRequestMetrics.Snapshot snapshot
    ) {
        if (!LOGGER.isInfoEnabled() || request == null) {
            return;
        }
        Object handlerDurObj = request.getAttribute(PerformanceViewTimingInterceptor.ATTR_HANDLER_DURATION);
        Object viewDurObj = request.getAttribute(PerformanceViewTimingInterceptor.ATTR_VIEW_DURATION);
        String path = diagnosticPath(request);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
                Locale.ROOT,
                "[PERF-DIAGNOSTIC] %s request: total=%.2fms",
                path,
                milliseconds(elapsedNanos)
        ));
        if (handlerDurObj instanceof Long hDur) {
            sb.append(String.format(Locale.ROOT, ", ctrl=%.2fms", milliseconds(hDur)));
        }
        if (viewDurObj instanceof Long vDur) {
            sb.append(String.format(Locale.ROOT, ", view_est=%.2fms", milliseconds(vDur)));
        }
        sb.append(String.format(
                Locale.ROOT,
                " | JDBC: conn=%.2fms (%d acq), sql=%.2fms (%d stmts), tx=%.2fms (%d calls [",
                milliseconds(snapshot.connectionAcquisitionNanos()),
                snapshot.connectionAcquisitionCount(),
                milliseconds(snapshot.sqlExecutionNanos()),
                snapshot.sqlStatementCount(),
                milliseconds(snapshot.transactionControlNanos()),
                snapshot.transactionControlCount()
        ));

        sb.append(String.format(
                Locale.ROOT,
                "setAutoCommit: %d (%.2fms, max=%.2fms), setReadOnly: %d (%.2fms, max=%.2fms), setTransactionIsolation: %d (%.2fms, max=%.2fms), commit: %d (%.2fms, max=%.2fms), rollback: %d (%.2fms, max=%.2fms)",
                snapshot.setAutoCommit().count(), milliseconds(snapshot.setAutoCommit().durationNanos()), milliseconds(snapshot.setAutoCommit().maxDurationNanos()),
                snapshot.setReadOnly().count(), milliseconds(snapshot.setReadOnly().durationNanos()), milliseconds(snapshot.setReadOnly().maxDurationNanos()),
                snapshot.setTransactionIsolation().count(), milliseconds(snapshot.setTransactionIsolation().durationNanos()), milliseconds(snapshot.setTransactionIsolation().maxDurationNanos()),
                snapshot.commit().count(), milliseconds(snapshot.commit().durationNanos()), milliseconds(snapshot.commit().maxDurationNanos()),
                snapshot.rollback().count(), milliseconds(snapshot.rollback().durationNanos()), milliseconds(snapshot.rollback().maxDurationNanos())
        ));
        sb.append("])");

        long measuredNanos = snapshot.connectionAcquisitionNanos()
                + snapshot.sqlExecutionNanos()
                + snapshot.transactionControlNanos();
        long appNanos = Math.max(0, elapsedNanos - measuredNanos);
        sb.append(String.format(Locale.ROOT, ", app_residual=%.2fms", milliseconds(appNanos)));

        LOGGER.info(sb.toString());
    }

    private static double milliseconds(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static final class FirstByteTimingResponseWrapper extends HttpServletResponseWrapper {

        private final PerformanceRequestMetrics metrics;
        private final long started;
        private final LongSupplier nanoTime;
        private final HttpServletRequest request;
        private final AtomicBoolean published = new AtomicBoolean();
        private ServletOutputStream outputStream;
        private PrintWriter writer;

        private FirstByteTimingResponseWrapper(
                HttpServletResponse response,
                PerformanceRequestMetrics metrics,
                long started,
                LongSupplier nanoTime,
                HttpServletRequest request
        ) {
            super(response);
            this.metrics = metrics;
            this.started = started;
            this.nanoTime = nanoTime;
            this.request = request;
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (outputStream == null) {
                ServletOutputStream delegate = super.getOutputStream();
                outputStream = new ServletOutputStream() {
                    @Override
                    public boolean isReady() {
                        return delegate.isReady();
                    }

                    @Override
                    public void setWriteListener(WriteListener writeListener) {
                        delegate.setWriteListener(writeListener);
                    }

                    @Override
                    public void write(int value) throws IOException {
                        publish("firstbyte");
                        delegate.write(value);
                    }

                    @Override
                    public void write(byte[] bytes, int offset, int length) throws IOException {
                        publish("firstbyte");
                        delegate.write(bytes, offset, length);
                    }

                    @Override
                    public void flush() throws IOException {
                        publish("firstbyte");
                        delegate.flush();
                    }

                    @Override
                    public void close() throws IOException {
                        publish("firstbyte");
                        delegate.close();
                    }
                };
            }
            return outputStream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                PrintWriter delegate = super.getWriter();
                writer = new PrintWriter(new FilterWriter(delegate) {
                    @Override
                    public void write(int value) throws IOException {
                        publish("firstbyte");
                        super.write(value);
                    }

                    @Override
                    public void write(char[] characters, int offset, int length) throws IOException {
                        publish("firstbyte");
                        super.write(characters, offset, length);
                    }

                    @Override
                    public void write(String value, int offset, int length) throws IOException {
                        publish("firstbyte");
                        super.write(value, offset, length);
                    }

                    @Override
                    public void flush() throws IOException {
                        publish("firstbyte");
                        super.flush();
                    }

                    @Override
                    public void close() throws IOException {
                        publish("firstbyte");
                        super.close();
                    }
                });
            }
            return writer;
        }

        @Override
        public void flushBuffer() throws IOException {
            publish("firstbyte");
            super.flushBuffer();
        }

        @Override
        public void sendError(int statusCode) throws IOException {
            publish("total");
            super.sendError(statusCode);
        }

        @Override
        public void sendError(int statusCode, String message) throws IOException {
            publish("total");
            super.sendError(statusCode, message);
        }

        @Override
        public void sendRedirect(String location) throws IOException {
            publish("total");
            super.sendRedirect(location);
        }

        private void publish(String primaryMetric) {
            if (published.compareAndSet(false, true) && !isCommitted()) {
                long elapsed = nanoTime.getAsLong() - started;
                PerformanceRequestMetrics.Snapshot snapshot = metrics.snapshot();
                setHeader(SERVER_TIMING, headerValue(primaryMetric, elapsed, snapshot, request));
                logDiagnostics(request, elapsed, snapshot);
            }
        }
    }
}
