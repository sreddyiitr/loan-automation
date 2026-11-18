package com.funding.api.trigger;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

/** AS-1.7: {@code /api/v1/funding-workflows/**} needs {@code X-Trigger-Api-Key} = {@code FUNDING_TRIGGER_API_KEY}. */
public class TriggerApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trigger-Api-Key";
    private final byte[] key;

    public TriggerApiKeyFilter(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("funding.trigger.api-key (FUNDING_TRIGGER_API_KEY) is required");
        }
        this.key = key.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/funding-workflows");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String given = req.getHeader(HEADER);
        if (given == null || !MessageDigest.isEqual(key, given.getBytes(StandardCharsets.UTF_8))) {
            resp.setStatus(401);
            resp.setContentType("application/problem+json");
            resp.getWriter().write("{\"type\":\"about:blank\",\"title\":\"UNAUTHORIZED\",\"status\":401,"
                    + "\"detail\":\"missing or invalid " + HEADER + "\",\"reason\":\"UNAUTHORIZED\"}");
            return;
        }
        chain.doFilter(req, resp);
    }
}
