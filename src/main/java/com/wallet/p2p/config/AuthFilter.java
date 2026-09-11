package com.wallet.p2p.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AuthFilter extends OncePerRequestFilter {

    public static final String MDC_USER_ID_KEY = "user_id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // Allow actuator, logs, and swagger/openapi endpoints without auth
        if (path.startsWith("/actuator") || path.startsWith("/logs") || path.startsWith("/error")) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        String userId = null;

        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            userId = authHeader.substring(7).trim();
        } else {
            // Support fallback header X-User-Id for testing ease
            userId = request.getHeader("X-User-Id");
        }

        if (StringUtils.hasText(userId)) {
            SecurityUserContext.setCurrentUser(userId);
            MDC.put(MDC_USER_ID_KEY, userId);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityUserContext.clear();
            MDC.remove(MDC_USER_ID_KEY);
        }
    }
}
