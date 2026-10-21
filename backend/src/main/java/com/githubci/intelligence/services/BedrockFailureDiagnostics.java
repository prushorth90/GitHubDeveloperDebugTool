package com.githubci.intelligence.services;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.AccessDeniedException;
import java.nio.file.ReadOnlyFileSystemException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkException;

record BedrockFailureDiagnostics(Reason reason, List<String> causeTypes, String service,
                                 String errorCode, int httpStatus, String requestId) {
    enum Reason {
        SSO_CACHE_WRITE_FAILED("Refresh SSO on the host; keep the mounted token cache read-only."),
        CREDENTIALS_EXPIRED("Refresh the selected AWS profile on the host and restart the backend."),
        CREDENTIALS_REJECTED("Authenticate the selected AWS profile again and check its credentials configuration."),
        SSO_TOKEN_UNAVAILABLE("Check the selected SSO session, host login and readable cache mount."),
        PROFILE_UNAVAILABLE("Check BEDROCK_AWS_PROFILE and the mounted AWS config file."),
        CREDENTIALS_UNAVAILABLE("Check the Bedrock-specific credentials provider; do not replace emulator credentials."),
        ACCESS_DENIED("Check bedrock:InvokeModel, model access, inference-profile destinations and SCPs."),
        MODEL_NOT_FOUND("Check BEDROCK_MODEL_ID and BEDROCK_REGION."),
        INVALID_REQUEST("Check model/region compatibility, inference-profile ID and Converse parameters."),
        THROTTLED("Check Bedrock quotas and invocation rate."),
        TIMEOUT("Check connectivity and the configured SDK call timeouts."),
        DNS_FAILURE("Check container DNS and the regional AWS endpoint."),
        CONNECTION_FAILURE("Check container egress, proxy and AWS endpoint connectivity."),
        TLS_FAILURE("Check the container trust store and TLS proxy configuration."),
        AWS_SERVICE_ERROR("Use the AWS error code, HTTP status and request ID to investigate the service failure."),
        SDK_CLIENT_ERROR("Check the cause types, selected AWS profile, local SSO session and container connectivity.");

        private final String action;

        Reason(String action) { this.action = action; }
        String action() { return action; }
    }

    static BedrockFailureDiagnostics from(SdkException exception) {
        List<Throwable> causes = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = exception; cause != null && causes.size() < 8 && seen.add(cause);
                cause = cause.getCause()) {
            causes.add(cause);
        }
        AwsServiceException serviceError = causes.stream().filter(AwsServiceException.class::isInstance)
                .map(AwsServiceException.class::cast).findFirst().orElse(null);
        String code = serviceError == null || serviceError.awsErrorDetails() == null
                ? "" : serviceError.awsErrorDetails().errorCode();
        String service = serviceError == null || serviceError.awsErrorDetails() == null
                ? "" : serviceError.awsErrorDetails().serviceName();
        int status = serviceError == null ? 0 : serviceError.statusCode();
        return new BedrockFailureDiagnostics(reason(causes, code, status),
                causes.stream().map(cause -> cause.getClass().getSimpleName()).toList(),
                safeIdentifier(service), safeIdentifier(code), status,
                safeIdentifier(serviceError == null ? "" : serviceError.requestId()));
    }

    private static Reason reason(List<Throwable> causes, String code, int status) {
        if ("ExpiredToken".equals(code) || "ExpiredTokenException".equals(code)) return Reason.CREDENTIALS_EXPIRED;
        if (Set.of("InvalidGrantException", "InvalidClientTokenId", "UnrecognizedClientException",
                "UnauthorizedException").contains(code == null ? "" : code)) return Reason.CREDENTIALS_REJECTED;
        if ("AccessDeniedException".equals(code) || status == 403) return Reason.ACCESS_DENIED;
        if ("ResourceNotFoundException".equals(code)) return Reason.MODEL_NOT_FOUND;
        if ("ValidationException".equals(code)) return Reason.INVALID_REQUEST;
        if ("ThrottlingException".equals(code) || status == 429) return Reason.THROTTLED;
        if (status > 0) return Reason.AWS_SERVICE_ERROR;
        // Match known SDK diagnostics, but never emit exception messages or response bodies.
        String messages = causes.stream().map(Throwable::getMessage).filter(message -> message != null)
                .map(message -> message.toLowerCase(Locale.ROOT)).reduce("", (first, next) -> first + "\n" + next);
        if (messages.contains("unable to write token")
                || messages.contains("token") && causes.stream().anyMatch(cause ->
                        cause instanceof ReadOnlyFileSystemException || cause instanceof AccessDeniedException
                                || cause.getMessage() != null
                                && cause.getMessage().toLowerCase(Locale.ROOT).contains("read-only file system"))) {
            return Reason.SSO_CACHE_WRITE_FAILED;
        }
        if (messages.contains("expired") && (messages.contains("token") || messages.contains("credential"))) {
            return Reason.CREDENTIALS_EXPIRED;
        }
        if (messages.contains("unable to load sso token") || messages.contains("failed to load cached token")
                || messages.contains("sso session") && messages.contains("invalid")) {
            return Reason.SSO_TOKEN_UNAVAILABLE;
        }
        if (messages.contains("profile") && (messages.contains("no credentials")
                || messages.contains("not found") || messages.contains("could not be found"))) {
            return Reason.PROFILE_UNAVAILABLE;
        }
        if (messages.contains("unable to load credentials") || messages.contains("unable to load aws credentials")) {
            return Reason.CREDENTIALS_UNAVAILABLE;
        }
        if (causes.stream().anyMatch(cause -> cause instanceof ApiCallTimeoutException
                || cause instanceof ApiCallAttemptTimeoutException || cause instanceof SocketTimeoutException)) {
            return Reason.TIMEOUT;
        }
        if (causes.stream().anyMatch(UnknownHostException.class::isInstance)) return Reason.DNS_FAILURE;
        if (causes.stream().anyMatch(ConnectException.class::isInstance)) return Reason.CONNECTION_FAILURE;
        if (causes.stream().anyMatch(javax.net.ssl.SSLException.class::isInstance)) return Reason.TLS_FAILURE;
        return Reason.SDK_CLIENT_ERROR;
    }

    static String safeIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_./:+#-]{1,256}") ? value : "UNAVAILABLE";
    }
}
