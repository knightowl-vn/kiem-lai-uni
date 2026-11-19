package com.universe.configuration.performance;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import java.util.Objects;
import java.util.function.LongSupplier;

final class PerformanceViewTimingInterceptor implements HandlerInterceptor {

    static final String ATTR_HANDLER_STARTED = "perf.handler.started.nanos";
    static final String ATTR_HANDLER_DURATION = "perf.handler.duration.nanos";
    static final String ATTR_VIEW_STARTED = "perf.view.started.nanos";
    static final String ATTR_VIEW_DURATION = "perf.view.duration.nanos";

    private final LongSupplier nanoTime;

    PerformanceViewTimingInterceptor() {
        this(System::nanoTime);
    }

    PerformanceViewTimingInterceptor(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) {
        request.setAttribute(ATTR_HANDLER_STARTED, nanoTime.getAsLong());
        return true;
    }

    @Override
    public void postHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            ModelAndView modelAndView
    ) {
        Object startedObj = request.getAttribute(ATTR_HANDLER_STARTED);
        if (startedObj instanceof Long started) {
            long now = nanoTime.getAsLong();
            request.setAttribute(ATTR_HANDLER_DURATION, Math.max(0, now - started));
            request.setAttribute(ATTR_VIEW_STARTED, now);
        }
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex
    ) {
        Object viewStartedObj = request.getAttribute(ATTR_VIEW_STARTED);
        if (viewStartedObj instanceof Long viewStarted) {
            long now = nanoTime.getAsLong();
            request.setAttribute(ATTR_VIEW_DURATION, Math.max(0, now - viewStarted));
        }
    }
}
