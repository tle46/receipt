package com.cs.receipt.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.*;

/** Per-instance abuse control. Do not trust client-supplied forwarding headers. */
@Component
public class AuthRateLimiter extends OncePerRequestFilter {
    private final Map<String, Window> windows = new HashMap<>();
    private record Window(long start, int count) {}
    public synchronized boolean allow(String key, int limit) {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(e -> now - e.getValue().start >= 60_000);
        Window old = windows.get(key);
        if (old == null && windows.size() >= 10000) return false;
        Window next = old == null ? new Window(now, 1) : new Window(old.start, old.count + 1);
        windows.put(key, next);
        return next.count <= limit;
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (org.springframework.web.util.UriUtils.decode(req.getRequestURI().substring(req.getContextPath().length()), java.nio.charset.StandardCharsets.UTF_8).startsWith("/api/auth/") && !req.getMethod().equals("OPTIONS")
                && !allow("ip:" + req.getRemoteAddr(), 30)) {
            res.setStatus(429); res.setHeader("Retry-After", "60"); return;
        }
        chain.doFilter(req, res);
    }
}
