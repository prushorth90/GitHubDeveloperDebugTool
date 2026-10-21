package com.githubci.intelligence.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class LocalMcpFilterTest {
    private final LocalMcpFilter filter = new LocalMcpFilter();

    @Test
    void acceptsDirectLoopbackClientsAndSameOrigin() throws Exception {
        var request = request();
        request.addHeader("Origin", "http://localhost:8080");
        var chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
    }

    @Test
    void acceptsIpv6Loopback() throws Exception {
        var request = request();
        request.setRemoteAddr("::1");
        request.setLocalAddr("0:0:0:0:0:0:0:1");
        request.removeHeader("Host");
        request.addHeader("Host", "[::1]:8080");
        var chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"attacker.example", "localhost.attacker.example", "127.0.0.1.attacker.example",
            "localhost:8080/path", "localhost:8080@attacker.example", "localhost:99999", "invalid host"})
    void rejectsNonLocalOrMalformedHost(String host) throws Exception {
        var request = request();
        request.removeHeader("Host");
        request.addHeader("Host", host);
        forbidden(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://attacker.example", "http://localhost:3000", "null"})
    void rejectsCrossOriginBrowserAccess(String origin) throws Exception {
        var request = request();
        request.addHeader("Origin", origin);
        forbidden(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto"})
    void rejectsProxyForwardingHeaders(String header) throws Exception {
        var request = request();
        request.addHeader(header, "127.0.0.1");
        forbidden(request);
    }

    @Test
    void rejectsNonLoopbackPeerOrLocalInterfaceAndMissingHost() throws Exception {
        var remote = request();
        remote.setRemoteAddr("192.168.1.2");
        forbidden(remote);
        var local = request();
        local.setLocalAddr("192.168.1.3");
        forbidden(local);
        var missingHost = request();
        missingHost.removeHeader("Host");
        forbidden(missingHost);
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/mcp");
        request.setRemoteAddr("127.0.0.1");
        request.setLocalAddr("127.0.0.1");
        request.addHeader("Host", "localhost:8080");
        return request;
    }

    private void forbidden(MockHttpServletRequest request) throws Exception {
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertEquals(403, response.getStatus());
        assertNull(chain.getRequest());
    }
}
