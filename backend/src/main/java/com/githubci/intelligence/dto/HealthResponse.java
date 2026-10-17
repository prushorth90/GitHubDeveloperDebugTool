package com.githubci.intelligence.dto;

import java.time.Instant;

public record HealthResponse(String status, String service, Instant timestamp) {
}
