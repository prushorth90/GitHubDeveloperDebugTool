package com.githubci.intelligence.services;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.AccessDeniedException;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.bedrockruntime.model.BedrockRuntimeException;

import static com.githubci.intelligence.services.BedrockFailureDiagnostics.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class BedrockFailureDiagnosticsTest {
    static Stream<Arguments> clientFailures() {
        return Stream.of(
                Arguments.of(SdkClientException.create("Token is expired"), CREDENTIALS_EXPIRED),
                Arguments.of(SdkClientException.create("Unable to load SSO token"), SSO_TOKEN_UNAVAILABLE),
                Arguments.of(SdkClientException.create("Failed to load cached token at private-path"), SSO_TOKEN_UNAVAILABLE),
                Arguments.of(SdkClientException.create("Profile file contained no credentials for profile"),
                        PROFILE_UNAVAILABLE),
                Arguments.of(SdkClientException.create("Unable to load credentials from any of the providers"),
                        CREDENTIALS_UNAVAILABLE),
                Arguments.of(SdkClientException.create("Unable to write token to location",
                        new AccessDeniedException("private-path")), SSO_CACHE_WRITE_FAILED),
                Arguments.of(SdkClientException.create("request", new SocketTimeoutException("private-host")), TIMEOUT),
                Arguments.of(SdkClientException.create("request", new UnknownHostException("private-host")), DNS_FAILURE),
                Arguments.of(SdkClientException.create("request", new ConnectException("private-host")), CONNECTION_FAILURE),
                Arguments.of(SdkClientException.create("request", new javax.net.ssl.SSLException("private-host")), TLS_FAILURE),
                Arguments.of(SdkClientException.create("private arbitrary payload"), SDK_CLIENT_ERROR));
    }

    @ParameterizedTest
    @MethodSource("clientFailures")
    void classifiesLocalFailuresWithoutExposingExceptionText(SdkException exception,
            BedrockFailureDiagnostics.Reason expected) {
        var diagnostic = BedrockFailureDiagnostics.from(exception);
        assertEquals(expected, diagnostic.reason());
        assertFalse(diagnostic.reason().action().isBlank());
        assertEquals(0, diagnostic.httpStatus());
        assertEquals("UNAVAILABLE", diagnostic.requestId());
        assertFalse(diagnostic.toString().contains("private"));
    }

    static Stream<Arguments> serviceFailures() {
        return Stream.of(
                Arguments.of("ExpiredTokenException", 400, CREDENTIALS_EXPIRED),
                Arguments.of("InvalidGrantException", 400, CREDENTIALS_REJECTED),
                Arguments.of("AccessDeniedException", 403, ACCESS_DENIED),
                Arguments.of("ResourceNotFoundException", 404, MODEL_NOT_FOUND),
                Arguments.of("ValidationException", 400, INVALID_REQUEST),
                Arguments.of("ThrottlingException", 429, THROTTLED),
                Arguments.of("ServiceUnavailableException", 503, AWS_SERVICE_ERROR));
    }

    @ParameterizedTest
    @MethodSource("serviceFailures")
    void preservesAwsDiagnosticMetadataFromWrappedExceptions(String code, int status,
            BedrockFailureDiagnostics.Reason expected) {
        var service = BedrockRuntimeException.builder().message("private response body")
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).serviceName("BedrockRuntime")
                        .errorMessage("private prompt content").build())
                .statusCode(status).requestId("request-123").build();
        var diagnostic = BedrockFailureDiagnostics.from(SdkClientException.create("wrapper", service));
        assertEquals(expected, diagnostic.reason());
        assertEquals(code, diagnostic.errorCode());
        assertEquals(status, diagnostic.httpStatus());
        assertEquals("request-123", diagnostic.requestId());
        assertEquals("BedrockRuntime", diagnostic.service());
        assertEquals(List.of("SdkClientException", "BedrockRuntimeException"), diagnostic.causeTypes());
        assertFalse(diagnostic.toString().contains("private"));
    }

    @Test
    void toleratesMissingServiceMetadataAndBoundsCyclesInCauseChains() {
        var service = BedrockRuntimeException.builder().statusCode(500).build();
        var diagnostic = BedrockFailureDiagnostics.from(service);
        assertEquals(AWS_SERVICE_ERROR, diagnostic.reason());
        assertEquals("UNAVAILABLE", diagnostic.errorCode());
        var first = new IllegalStateException("private");
        var second = new IllegalStateException("private", first);
        first.initCause(second);
        diagnostic = BedrockFailureDiagnostics.from(SdkClientException.create("wrapper", first));
        assertEquals(3, diagnostic.causeTypes().size());
        assertEquals(SDK_CLIENT_ERROR, diagnostic.reason());
    }

    @Test
    void rejectsUnstructuredOrMultilineMetadataInsteadOfLoggingIt() {
        assertEquals("UNAVAILABLE", BedrockFailureDiagnostics.safeIdentifier("private payload\nanother line"));
        assertEquals("UNAVAILABLE", BedrockFailureDiagnostics.safeIdentifier("x".repeat(257)));
        assertEquals("UNAVAILABLE", BedrockFailureDiagnostics.safeIdentifier(null));
        assertEquals("us.amazon.nova-lite-v1:0",
                BedrockFailureDiagnostics.safeIdentifier("us.amazon.nova-lite-v1:0"));
    }

    @Test
    void capsLongCauseChainsAndPrefersStructuredAwsCodesOverMessageHeuristics() {
        Throwable nested = new IllegalStateException("private");
        for (int index = 0; index < 20; index++) nested = new IllegalStateException("private", nested);
        assertEquals(8, BedrockFailureDiagnostics.from(SdkClientException.create("wrapper", nested)).causeTypes().size());
        var service = BedrockRuntimeException.builder().message("user supplied text: Token is expired")
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("ValidationException").build())
                .statusCode(400).build();
        assertEquals(INVALID_REQUEST, BedrockFailureDiagnostics.from(service).reason());
    }
}
