package com.githubci.intelligence.controllers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.githubci.intelligence.services.HealthService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HealthControllerTest {

    @Test
    void returnsServiceHealthWithCurrentTimestamp() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-10-17T03:00:00Z"), ZoneOffset.UTC);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new HealthController(new HealthService("github-ci-intelligence", clock))).build();

        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("github-ci-intelligence"))
                .andExpect(jsonPath("$.timestamp").value("2026-10-17T03:00:00Z"));
    }
}
