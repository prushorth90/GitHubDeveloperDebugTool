package com.githubci.intelligence.services;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.githubci.intelligence.dto.ConfidenceLevel;
import com.githubci.intelligence.dto.FailureAnalysis;
import com.githubci.intelligence.dto.FailureCategory;
import com.githubci.intelligence.dto.LogEvidence;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import static com.githubci.intelligence.dto.ConfidenceLevel.HIGH;
import static com.githubci.intelligence.dto.ConfidenceLevel.LOW;
import static com.githubci.intelligence.dto.ConfidenceLevel.MEDIUM;
import static com.githubci.intelligence.dto.FailureCategory.AUTHENTICATION;
import static com.githubci.intelligence.dto.FailureCategory.DOCKER_BUILD;
import static com.githubci.intelligence.dto.FailureCategory.GITHUB_ACTIONS_CONFIGURATION;
import static com.githubci.intelligence.dto.FailureCategory.JAVA_COMPILATION;
import static com.githubci.intelligence.dto.FailureCategory.JUNIT_TEST;
import static com.githubci.intelligence.dto.FailureCategory.MAVEN_DEPENDENCY;
import static com.githubci.intelligence.dto.FailureCategory.POSTGRESQL_CONNECTION;
import static com.githubci.intelligence.dto.FailureCategory.UNKNOWN;

@Service
public class FailureAnalyzerService {

    private final FailureFingerprintService fingerprintService;

    public FailureAnalyzerService() {
        this(new FailureFingerprintService());
    }

    @Autowired
    public FailureAnalyzerService(FailureFingerprintService fingerprintService) {
        this.fingerprintService = fingerprintService;
    }

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern TIMESTAMP = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z\\s*");
    private static final Pattern GENERIC_ERROR = pattern(
            "\\b(error|exception|failed|failure|fatal|ECONNREFUSED)\\b|##\\[error\\]");
    private static final Pattern CONNECTION_FAILURE = pattern(
            "ECONNREFUSED|connection (?:to .* )?(?:refused|timed out)|"
                    + "could not connect|unable to connect|connection attempt failed|"
                    + "no pg_hba\\.conf entry|password authentication failed|"
                    + "remaining connection slots|too many (?:clients|connections)");
    private static final Pattern POSTGRES_CONNECTION_CONTEXT = pattern(
            "jdbc:postgresql:|org\\.postgresql\\.|"
                    + "(?:connect(?:ing|ion)?|psql).*\\bpostgres(?:ql)?\\b|"
                    + "\\bpostgres(?:ql)?\\b.*connect(?:ion|ing)?");
    private static final Pattern JAVA_ASSERTION = pattern("java\\.lang\\.AssertionError");
    private static final Pattern EXPLICIT_NETWORK_TARGET = pattern(
            "ECONNREFUSED\\s+\\S+:\\d+|https?://");
    private static final Pattern BOUNDARY = pattern(
            "^##\\[(?:group|endgroup)\\]|^##\\[command\\]|^Run\\s");
    private static final Pattern JAVA_TEST_CONTEXT = pattern(
            "\\bJUnit\\b|org\\.junit\\.|maven-(?:surefire|failsafe)-plugin|"
                    + "\\b(?:surefire|failsafe)-reports\\b");

    // Order breaks ties on the same line; specific diagnostics outrank build wrappers.
    private static final List<Rule> RULES = List.of(
            rule(GITHUB_ACTIONS_CONFIGURATION, HIGH,
                    "invalid workflow file|workflow is not valid|"
                            + "\\.github/workflows/.*(?:unexpected value|unrecognized named-value|"
                            + "not a valid|syntax error)|"
                            + "unable to resolve action\\b|"
                            + "can't find ['\"]?action\\.ya?ml|"
                            + "no event triggers defined in [`'\"]?on",
                    "The workflow or an action reference may be invalid.",
                    "Validate the workflow YAML, expressions, action paths and referenced versions."),
            rule(POSTGRESQL_CONNECTION, HIGH,
                    "(?:org\\.postgresql\\..*|psql:.*|postgres(?:ql)?.*)"
                            + "(?:connection.*(?:refused|failed|timed out)|could not connect|"
                            + "password authentication failed|no pg_hba\\.conf entry|"
                            + "too many clients|remaining connection slots)",
                    "The PostgreSQL endpoint, readiness, credentials or connection policy may be incorrect.",
                    "Check the PostgreSQL service health, host and port, JDBC URL, credentials and pg_hba.conf."),
            rule(AUTHENTICATION, HIGH,
                    "authentication failed|bad credentials|invalid (?:access )?token|"
                            + "token (?:has )?expired|requires authentication|"
                            + "permission denied \\(publickey\\)|could not read username|"
                            + "unauthorized:|denied: requested access to the resource is denied|"
                            + "resource not accessible by integration|"
                            + "(?:status(?: code)?|http(?:/\\d(?:\\.\\d)?)?|return code is|error:)\\s*[:=]?\\s*401\\b|"
                            + "403\\s+forbidden",
                    "Credentials may be missing, expired or insufficiently scoped.",
                    "Check secret availability, token expiry and permissions for the failing operation; "
                            + "do not print credentials."),
            rule(JAVA_COMPILATION, HIGH,
                    "\\.java:\\[\\d+,\\d+\\]|\\.java:\\d+:\\s*error:|"
                            + "\\b(?:javac|compiler)\\b.*(?:invalid target release|"
                            + "release version .* not supported)|"
                            + "\\[ERROR\\].*(?:cannot find symbol|package .+ does not exist|"
                            + "incompatible types|invalid target release|release version .* not supported)",
                    "Java sources, generated code, dependencies or the configured JDK may be incompatible.",
                    "Inspect the compiler diagnostic and source location; compare CI JDK, compiler release "
                            + "and compile classpath with the local build."),
            rule(MAVEN_DEPENDENCY, HIGH,
                    "could not resolve dependencies for project|"
                            + "could not (?:find|transfer) artifact\\b|"
                            + "failed to (?:collect dependencies|read artifact descriptor)|"
                            + "non-resolvable (?:parent|import) pom|"
                            + "plugin .+ or one of its dependencies could not be resolved",
                    "A Maven artifact or repository may be unavailable, misconfigured or inaccessible.",
                    "Inspect the artifact coordinates, repository URL, Maven settings, mirrors, credentials "
                            + "and network access before changing dependency versions."),
            rule(JUNIT_TEST, HIGH,
                    "tests run:\\s*\\d+[^\\r\\n]*"
                            + "(?:failures:\\s*[1-9]\\d*|errors:\\s*[1-9]\\d*)|"
                            + "org\\.opentest4j\\.(?:AssertionFailedError|MultipleFailuresError)|"
                            + "org\\.junit\\..*(?:AssertionError|ComparisonFailure)|"
                            + "<<< (?:FAILURE|ERROR)!",
                    "A Java test assertion or test setup may be failing.",
                    "Open the failing test and Surefire/Failsafe or JUnit report; inspect expected versus "
                            + "actual values and the first underlying exception."),
            rule(DOCKER_BUILD, MEDIUM,
                    "failed to solve:|failed to build:|"
                            + "dockerfile:\\d+|"
                            + "the command .+ returned a non-zero code|"
                            + "(?:COPY|ADD) failed:|"
                            + "failed to (?:read dockerfile|compute cache key)|"
                            + "error response from daemon:.*(?:dockerfile|build)",
                    "A Docker build instruction, build context or base image may be invalid.",
                    "Inspect the failed Dockerfile instruction and preceding command output, build context "
                            + "and base-image access; a build wrapper alone does not establish the root cause."),
            rule(JAVA_COMPILATION, MEDIUM,
                    "compilation (?:failure|error)|maven-compiler-plugin.*failed",
                    "The Java compilation phase may have failed; the exact compiler cause is not established.",
                    "Find the earlier compiler diagnostics and check the CI JDK and compile classpath."),
            rule(JUNIT_TEST, MEDIUM,
                    "there are test failures|maven-(?:surefire|failsafe)-plugin.*failed",
                    "The Java test phase may have failed; the failing test is not established.",
                    "Inspect Surefire/Failsafe reports and earlier test output for the underlying failure."));

    public FailureAnalysis analyze(String rawLogs) {
        if (rawLogs == null) {
            throw new IllegalArgumentException("Raw job logs must not be null");
        }
        List<String> lines = rawLogs.lines()
                .map(line -> TIMESTAMP.matcher(ANSI.matcher(line).replaceAll("")).replaceFirst("").strip())
                .toList();
        Map<FailureCategory, Match> matches = new EnumMap<>(FailureCategory.class);
        int firstError = -1;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (BOUNDARY.matcher(line).find()) {
                continue;
            }
            if (firstError < 0 && GENERIC_ERROR.matcher(line).find()) {
                firstError = index;
            }
            Match match = match(lines, index);
            if (match != null) {
                Match previous = matches.get(match.rule().category());
                if (previous == null || match.rule().confidence().compareTo(previous.rule().confidence()) > 0) {
                    matches.put(match.rule().category(), match);
                }
            }
        }
        List<Match> strongest = matches.values().stream()
                .filter(match -> match.rule().confidence() == HIGH).toList();
        if (strongest.isEmpty()) {
            strongest = List.copyOf(matches.values());
        }
        if (strongest.size() == 1) {
            Match match = strongest.getFirst();
            Rule rule = match.rule();
            return analysis(rule.category(), lines.get(match.index()), rule.cause(),
                    rule.investigation(), rule.confidence(), evidence(lines, match.evidenceIndices()),
                    context(lines, List.of(match.index())));
        }
        if (strongest.size() > 1) {
            List<Integer> indices = strongest.stream().map(Match::index).sorted().toList();
            return analysis(UNKNOWN, lines.get(indices.getFirst()),
                    "Multiple failure categories have supporting diagnostics; a single cause cannot be established.",
                    "Inspect the separate diagnostics and step boundaries to determine which failure occurred first "
                            + "and whether later errors are consequences.",
                    LOW, evidence(lines, indices), context(lines, indices));
        }
        List<Integer> indices = firstError < 0 ? List.of() : List.of(firstError);
        return analysis(UNKNOWN,
                firstError < 0 ? "No recognizable failure diagnostic found." : lines.get(firstError),
                "The available log evidence does not establish a supported failure category.",
                "Inspect the failed step and preceding output, obtain fuller logs if needed, and verify "
                        + "the actual target of any network error before attributing it to a database.",
                LOW, evidence(lines, indices), context(lines, indices));
    }

    private FailureAnalysis analysis(FailureCategory category, String errorMessage, String suspectedCause,
            String suggestedInvestigation, ConfidenceLevel confidence, List<LogEvidence> evidence,
            List<LogEvidence> logContext) {
        String signature = evidence.isEmpty() ? errorMessage
                : evidence.stream().map(LogEvidence::text).collect(Collectors.joining("\n"));
        FailureFingerprintService.Fingerprint fingerprint = fingerprintService.fingerprint(category, signature);
        return new FailureAnalysis(category, errorMessage, suspectedCause, suggestedInvestigation, confidence,
                evidence, logContext, fingerprint.normalizedSignature(), fingerprint.fingerprint());
    }

    private Match match(List<String> lines, int index) {
        String line = lines.get(index);
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(line).find()) {
                return new Match(rule, index, List.of(index));
            }
        }
        if (CONNECTION_FAILURE.matcher(line).find()) {
            // Only nearby connection-specific evidence counts, never a job-wide PostgreSQL mention.
            for (int nearby = Math.max(0, index - 2); nearby <= Math.min(lines.size() - 1, index + 2); nearby++) {
                if (POSTGRES_CONNECTION_CONTEXT.matcher(lines.get(nearby)).find()
                        && sameSection(lines, index, nearby)
                        && (nearby == index || !EXPLICIT_NETWORK_TARGET.matcher(line).find())) {
                    return new Match(ruleFor(POSTGRESQL_CONNECTION, HIGH), index,
                            index == nearby ? List.of(index) : List.of(Math.min(index, nearby), Math.max(index, nearby)));
                }
            }
        }
        if (JAVA_ASSERTION.matcher(line).find()) {
            for (int nearby = index + 1; nearby <= Math.min(lines.size() - 1, index + 2); nearby++) {
                if (JAVA_TEST_CONTEXT.matcher(lines.get(nearby)).find() && sameSection(lines, index, nearby)) {
                    return new Match(ruleFor(JUNIT_TEST, HIGH), index, List.of(index, nearby));
                }
            }
        }
        return null;
    }

    private boolean sameSection(List<String> lines, int first, int second) {
        for (int index = Math.min(first, second); index <= Math.max(first, second); index++) {
            if (BOUNDARY.matcher(lines.get(index)).find()) {
                return false;
            }
        }
        return true;
    }

    private List<LogEvidence> evidence(List<String> lines, List<Integer> indices) {
        return indices.stream().distinct().sorted()
                .map(index -> new LogEvidence(index + 1, lines.get(index))).toList();
    }

    private List<LogEvidence> context(List<String> lines, List<Integer> centers) {
        List<Integer> indices = new ArrayList<>();
        for (int center : centers) {
            for (int index = Math.max(0, center - 2); index <= Math.min(lines.size() - 1, center + 2); index++) {
                if (!lines.get(index).isBlank() && sameSection(lines, center, index)) {
                    indices.add(index);
                }
            }
        }
        return evidence(lines, indices);
    }

    private static Rule rule(FailureCategory category, ConfidenceLevel confidence, String regex,
            String cause, String investigation) {
        return new Rule(category, confidence, pattern(regex), cause, investigation);
    }

    private static Rule ruleFor(FailureCategory category, ConfidenceLevel confidence) {
        return RULES.stream().filter(rule -> rule.category() == category && rule.confidence() == confidence)
                .findFirst().orElseThrow();
    }

    private static Pattern pattern(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    private record Rule(FailureCategory category, ConfidenceLevel confidence, Pattern pattern,
            String cause, String investigation) {
    }

    private record Match(Rule rule, int index, List<Integer> evidenceIndices) {
    }
}
