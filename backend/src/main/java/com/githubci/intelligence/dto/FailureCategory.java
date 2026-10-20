package com.githubci.intelligence.dto;

public enum FailureCategory {
    JAVA_COMPILATION,
    MAVEN_DEPENDENCY,
    JUNIT_TEST,
    POSTGRESQL_CONNECTION,
    DOCKER_BUILD,
    AUTHENTICATION,
    GITHUB_ACTIONS_CONFIGURATION,
    UNKNOWN
}
