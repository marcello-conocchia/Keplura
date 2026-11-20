package com.keplura.server;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Logs one line per handled API request at info level:
 * {@code METHOD path -> status (Nms)}.
 *
 * <p>Logging convention (M1a, see docs/development.md): the line contains only
 * the HTTP method, the request path (never the query string), the response
 * status and the handler duration. No headers, bodies, cookies or query
 * parameters — any of them may carry credentials or user data.
 *
 * <p>Liveness probes are excluded at registration time ({@code RequestLoggingConfig})
 * so a busy health check cannot drown the log.
 */
public class RequestLoggingInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingInterceptor.class);

    /** Request attribute holding the nanosecond timestamp from preHandle. */
    static final String START_ATTR = "keplura.request.startNanos";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_ATTR, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Object start = request.getAttribute(START_ATTR);
        long millis = start instanceof Long nanos
                ? Math.max(0, (System.nanoTime() - nanos) / 1_000_000)
                : -1;
        if (ex != null) {
            log.info("{} {} -> {} ({}ms, exception)", request.getMethod(), request.getRequestURI(),
                    response.getStatus(), millis, ex);
        } else {
            log.info("{} {} -> {} ({}ms)", request.getMethod(), request.getRequestURI(),
                    response.getStatus(), millis);
        }
    }
}
