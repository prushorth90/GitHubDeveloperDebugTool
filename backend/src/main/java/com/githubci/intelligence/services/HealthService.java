package com.githubci.intelligence.services;

import java.time.Clock;
import java.time.Instant;

import com.githubci.intelligence.dto.HealthResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class HealthService {

    private final String serviceName;
    private final Clock clock;

    public HealthService(@Value("${spring.application.name}") String serviceName, Clock clock) {
        this.serviceName = serviceName;
        this.clock = clock;
    }

    public HealthResponse getHealth() {
        return new HealthResponse("UP", serviceName, Instant.now(clock));
    }
}
