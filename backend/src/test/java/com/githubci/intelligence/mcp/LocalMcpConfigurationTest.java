package com.githubci.intelligence.mcp;

import com.githubci.intelligence.services.FailureService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class LocalMcpConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LocalMcpConfiguration.class)
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
            .withBean(FailureService.class, () -> mock(FailureService.class));

    @Test
    void disabledUnlessExplicitlyEnabledWithLocalProfile() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .run(context -> assertFalse(context.containsBean("mcpServlet")));
        runner.withPropertyValues("app.mcp.enabled=true")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .run(context -> assertFalse(context.containsBean("mcpServlet")));
        runner.withPropertyValues("app.mcp.enabled=true")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local", "prod"))
                .run(context -> assertFalse(context.containsBean("mcpServlet")));
        runner.withPropertyValues("app.mcp.enabled=true")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .run(context -> assertFalse(context.containsBean("mcpServlet")));
    }

    @Test
    void registersServletOnlyForOptInLocalDevelopment() {
        runner.withPropertyValues("app.mcp.enabled=true")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.containsBean("mcpServlet"));
                    assertTrue(context.containsBean("localMcpFilter"));
                    assertTrue(context.containsBean("failureMcpServer"));
                });
    }
}
