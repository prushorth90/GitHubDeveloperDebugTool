package com.githubci.intelligence.mcp;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.githubci.intelligence.dto.DiagnosticFinding;
import com.githubci.intelligence.dto.FailureAnalysis;
import com.githubci.intelligence.dto.FailureCategory;
import com.githubci.intelligence.dto.FailureResponses;
import com.githubci.intelligence.dto.LogEvidence;
import com.githubci.intelligence.exceptions.FailureReadException;
import com.githubci.intelligence.services.FailureService;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

public final class FailureMcpTools {
    private static final Logger log = LoggerFactory.getLogger(FailureMcpTools.class);
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private final FailureService service;
    private final ObjectMapper mapper;

    public FailureMcpTools(FailureService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public List<SyncToolSpecification> specifications() {
        return List.of(
                tool("recent_workflow_failures", "Read a page of persisted failures, newest first. "
                        + "Use failure_analysis for diagnostics and provenance. No analysis is generated.",
                        pageProperties(), List.of("repositoryId"), args -> service.runs(
                                id(args, "repositoryId"), true, category(args), FailureService.Order.desc,
                                integer(args, "limit", 10, 1, 20), text(args, "nextToken"))),
                tool("failure_analysis", "Read stored failure diagnostics, evidence and provenance. "
                        + "Jobs are paginated; bounded excerpts are marked truncated. Never invokes Bedrock.",
                        detailProperties(), List.of("repositoryId", "runId"), this::detail),
                tool("similar_workflow_failures", "Read a page of persisted failures sharing an exact "
                        + "fingerprint with the source run, newest first. Not semantic similarity. "
                        + "Use failure_analysis for each result's diagnostics.",
                        similarProperties(), List.of("repositoryId", "runId"), args -> service.similar(
                                id(args, "repositoryId"), id(args, "runId"), attempt(args), category(args),
                                FailureService.Order.desc, integer(args, "limit", 10, 1, 20),
                                text(args, "nextToken"))),
                tool("repository_failure_statistics", "Read aggregate statistics over all persisted workflow "
                        + "attempts in a repository, not all GitHub runs. No Bedrock calls.",
                        Map.of("repositoryId", positiveId()), List.of("repositoryId"),
                        args -> service.statistics(id(args, "repositoryId"))));
    }

    private SyncToolSpecification tool(String name, String description, Map<String, Object> properties,
                                        List<String> required, Function<Map<String, Object>, Object> operation) {
        var schema = Map.<String, Object>of("type", "object", "properties", properties,
                "required", required, "additionalProperties", false);
        var tool = McpSchema.Tool.builder().name(name).description(description).inputSchema(schema)
                .annotations(new McpSchema.ToolAnnotations(null, true, false, true, false, false)).build();
        return SyncToolSpecification.builder().tool(tool).callHandler((context, request) -> {
            try {
                var args = request.arguments() == null ? Map.<String, Object>of() : request.arguments();
                validate(args, properties.keySet(), required);
                var response = Map.of("repositoryId", id(args, "repositoryId"),
                        "storage", "DYNAMODB", "readOnly", true, "data", operation.apply(args));
                String json = mapper.writeValueAsString(response);
                var result = McpSchema.CallToolResult.builder().structuredContent(response)
                        .addTextContent(json).isError(false).build();
                if (mapper.writeValueAsBytes(result).length > MAX_RESPONSE_BYTES) {
                    return error("RESPONSE_TOO_LARGE", "Response exceeds 256 KiB; request a smaller page.");
                }
                return result;
            } catch (InvalidArguments exception) {
                return error("INVALID_ARGUMENT", exception.getMessage());
            } catch (FailureReadException exception) {
                if (exception.getStatus().is5xxServerError()) {
                    log.error("MCP stored failure read failed for tool {}", name, exception);
                }
                return error(exception.getStatus().name(), exception.getMessage());
            } catch (RuntimeException exception) {
                log.error("Unexpected MCP failure in tool {}", name, exception);
                return error("INTERNAL_ERROR", "Stored analysis could not be retrieved.");
            }
        }).build();
    }

    private Object detail(Map<String, Object> args) {
        int offset = integer(args, "jobOffset", 0, 0, 100_000);
        int limit = integer(args, "jobLimit", 3, 1, 5);
        var failure = service.failure(id(args, "repositoryId"), id(args, "runId"), attempt(args));
        if (offset > failure.failedJobs().size()) {
            throw new InvalidArguments("jobOffset exceeds the number of failed jobs");
        }
        int end = Math.min(offset + limit, failure.failedJobs().size());
        var jobs = failure.failedJobs().subList(offset, end).stream().map(job -> {
            var analysis = bounded(job.analysis());
            var message = clip(job.logMessage());
            return new Job(job.jobId(), clip(job.name()), job.logStatus(), message, analysis,
                    !java.util.Objects.equals(analysis, job.analysis())
                            || !java.util.Objects.equals(message, job.logMessage())
                            || !java.util.Objects.equals(clip(job.name()), job.name()));
        }).toList();
        return new Detail(failure.run(), jobs, failure.failedJobs().size(),
                end < failure.failedJobs().size() ? end : null);
    }

    public record Job(long jobId, String name, com.githubci.intelligence.dto.WorkflowRunAnalysis.LogStatus logStatus,
                      String logMessage, FailureAnalysis analysis, boolean truncated) { }
    public record Detail(FailureResponses.Run run, List<Job> failedJobs, int totalFailedJobs, Integer nextJobOffset) { }

    private FailureAnalysis bounded(FailureAnalysis analysis) {
        if (analysis == null) {
            return null;
        }
        return new FailureAnalysis(analysis.category(), clip(analysis.errorMessage()), clip(analysis.suspectedCause()),
                clip(analysis.suggestedInvestigation()), analysis.confidence(), evidence(analysis.evidence()),
                evidence(analysis.logContext()), clip(analysis.normalizedSignature()), clip(analysis.fingerprint()),
                clip(analysis.analysisSource()), clip(analysis.modelIdentifier()),
                analysis.diagnosticFindings().stream().limit(10).map(finding -> new DiagnosticFinding(
                        finding.kind(), clip(finding.description()), finding.confidence(), evidence(finding.evidence())))
                        .toList(), clip(analysis.fallbackReason()));
    }

    private List<LogEvidence> evidence(List<LogEvidence> evidence) {
        return evidence.stream().limit(10)
                .map(line -> new LogEvidence(line.lineNumber(), clip(line.text()))).toList();
    }

    private String clip(String text) {
        return text == null || text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    private McpSchema.CallToolResult error(String code, String message) {
        var body = Map.of("error", Map.of("code", code, "message", message));
        return McpSchema.CallToolResult.builder().structuredContent(body)
                .addTextContent(mapper.writeValueAsString(body)).isError(true).build();
    }

    private static Map<String, Object> positiveId() {
        return Map.of("type", "integer", "minimum", 1, "maximum", Long.MAX_VALUE);
    }

    private static Map<String, Object> pageProperties() {
        return new LinkedHashMap<>(Map.of("repositoryId", positiveId(),
                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 20, "default", 10),
                "nextToken", Map.of("type", "string", "minLength", 1, "maxLength", 4096),
                "category", Map.of("type", "string", "enum",
                        java.util.Arrays.stream(FailureCategory.values()).map(Enum::name).toList())));
    }

    private static Map<String, Object> similarProperties() {
        var properties = pageProperties();
        properties.put("runId", positiveId());
        properties.put("attempt", Map.of("type", "integer", "minimum", 1, "maximum", Integer.MAX_VALUE));
        return properties;
    }

    private static Map<String, Object> detailProperties() {
        var properties = similarProperties();
        properties.remove("category");
        properties.remove("limit");
        properties.remove("nextToken");
        properties.put("jobOffset", Map.of("type", "integer", "minimum", 0, "maximum", 100_000, "default", 0));
        properties.put("jobLimit", Map.of("type", "integer", "minimum", 1, "maximum", 5, "default", 3));
        return properties;
    }

    private void validate(Map<String, Object> args, Set<String> allowed, List<String> required) {
        if (!allowed.containsAll(args.keySet()) || args.values().stream().anyMatch(java.util.Objects::isNull)) {
            throw new InvalidArguments("Unknown or null tool argument");
        }
        for (String name : required) {
            if (!args.containsKey(name)) {
                throw new InvalidArguments("Missing required argument: " + name);
            }
        }
    }

    private long id(Map<String, Object> args, String name) {
        return number(args.get(name), name, 1, Long.MAX_VALUE);
    }

    private int integer(Map<String, Object> args, String name, int fallback, int minimum, int maximum) {
        return args.containsKey(name) ? (int) number(args.get(name), name, minimum, maximum) : fallback;
    }

    private long number(Object value, String name, long minimum, long maximum) {
        if (value instanceof Number number) {
            try {
                long result = new BigDecimal(number.toString()).longValueExact();
                if (result >= minimum && result <= maximum) {
                    return result;
                }
            } catch (ArithmeticException | NumberFormatException exception) {
                throw new InvalidArguments(name + " must be an integer in range " + minimum + "-" + maximum);
            }
        }
        throw new InvalidArguments(name + " must be an integer in range " + minimum + "-" + maximum);
    }

    private Integer attempt(Map<String, Object> args) {
        return args.containsKey("attempt") ? integer(args, "attempt", 1, 1, Integer.MAX_VALUE) : null;
    }

    private String text(Map<String, Object> args, String name) {
        if (!args.containsKey(name)) {
            return null;
        }
        if (args.get(name) instanceof String value && !value.isBlank() && value.length() <= 4096) {
            return value;
        }
        throw new InvalidArguments(name + " must be a nonblank string of at most 4096 characters");
    }

    private FailureCategory category(Map<String, Object> args) {
        if (!args.containsKey("category")) {
            return null;
        }
        String value = text(args, "category");
        try {
            return FailureCategory.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new InvalidArguments("Unknown failure category");
        }
    }

    private static final class InvalidArguments extends RuntimeException {
        private InvalidArguments(String message) { super(message); }
    }
}
