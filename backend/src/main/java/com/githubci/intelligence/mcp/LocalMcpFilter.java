package com.githubci.intelligence.mcp;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

public final class LocalMcpFilter extends OncePerRequestFilter {
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = request.getHeader("Host");
        String origin = request.getHeader("Origin");
        boolean forwarded = Collections.list(request.getHeaderNames()).stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .anyMatch(name -> name.equals("forwarded") || name.startsWith("x-forwarded-"));
        if (!LOOPBACK.contains(request.getRemoteAddr()) || !LOOPBACK.contains(request.getLocalAddr())
                || forwarded || !localAuthority(host) || origin != null && !sameOrigin(origin, host)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "MCP is restricted to direct loopback clients");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean localAuthority(String authority) {
        if (authority == null) {
            return false;
        }
        try {
            var uri = URI.create("http://" + authority);
            return uri.getHost() != null && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getPort() >= -1 && uri.getPort() <= 65535;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean sameOrigin(String origin, String host) {
        return origin.equals("http://" + host);
    }
}
