package com.githubci.intelligence;

import java.time.Instant;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.profiles.active=local", "github.token=", "app.bedrock.enabled=false"})
class GitHubCiIntelligenceApplicationTests {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void healthUsesConfiguredServiceNameAndCurrentTime() throws Exception {
        Instant before = Instant.now();
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("github-ci-intelligence"))
                .andExpect(result -> {
                    String value = JsonPath.read(result.getResponse().getContentAsString(), "$.timestamp");
                    Instant timestamp = Instant.parse(value);
                    assertTrue(!timestamp.isBefore(before) && !timestamp.isAfter(Instant.now()));
                });
    }

    @Test
    void githubRoutesAreWiredAndReportMissingTokenWithoutAffectingHealth() throws Exception {
        mvc.perform(get("/api/github/repositories/octo/demo/workflows"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("GitHub integration requires GITHUB_TOKEN"));
        mvc.perform(get("/api/github/repositories/octo/demo/runs/42/jobs"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
        mvc.perform(post("/api/github/repositories/octo/demo/runs/42/analyze"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
        mvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    @Test
    void allowsLocalReactOriginsAndPreflightRequests() throws Exception {
        for (String origin : new String[] {
                "http://localhost:5173", "http://127.0.0.1:5173",
                "http://localhost:3000", "http://127.0.0.1:3000"}) {
            mvc.perform(get("/api/health").header("Origin", origin))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin));
            mvc.perform(options("/api/health").header("Origin", origin)
                            .header("Access-Control-Request-Method", "GET")
                            .header("Access-Control-Request-Headers", "Content-Type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin));
        }
    }

    @Test
    void rejectsUnknownOrigins() throws Exception {
        mvc.perform(get("/api/health").header("Origin", "https://untrusted.example"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void exposesActuatorHealthWithoutDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void missingApiRoutesUseConsistentErrors() throws Exception {
        mvc.perform(get("/api/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value("/api/missing"));
    }
}
