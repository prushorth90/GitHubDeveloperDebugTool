package com.githubci.intelligence.services;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative log provenance hints, not a shell interpreter or proof of causality. */
final class JobLogContext {
    enum Kind { SHELL_COMMAND, PRINTED_OUTPUT, PROCESS_EXIT, APPLICATION_DIAGNOSTIC, STEP_METADATA, OTHER }

    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern TIMESTAMP = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z\\s*");
    private static final Pattern RUN = Pattern.compile("^(?:##\\[group\\])?Run\\s+");
    private static final Pattern PRINT = Pattern.compile(
            "^(?:\\+\\s*)?(?:echo|printf)\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ARGUMENT = Pattern.compile(
            "\"([^\"\\r\\n]*)\"|'([^'\\r\\n]*)'|([;&|<>]+)|([^\\s;&|<>\"']+)");
    private static final Pattern EXIT = Pattern.compile(
            "^(?:##\\[error\\])?(?:Error:\\s*)?Process completed with exit code\\s+\\d+\\.?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_EXIT = Pattern.compile(
            "^(?:\\+\\s*)?exit\\s+\\d+\\s*;?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIAGNOSTIC = Pattern.compile(
            "\\b(?:[\\w$]+\\.)+[\\w$]*(?:Exception|Error)\\b|"
                    + "^psql:\\s|\\bjdbc:postgresql:|\\bECONNREFUSED\\b|"
                    + "\\b(?:error|exception|fatal|failed|failure)\\b",
            Pattern.CASE_INSENSITIVE);

    private final List<String> lines;
    private final List<Kind> kinds;
    private final List<Integer> stepHeaders;
    private final List<String> diagnosticLines;

    JobLogContext(String logs) {
        this(logs.lines().toList());
    }

    JobLogContext(List<String> rawLines) {
        lines = rawLines.stream().map(JobLogContext::normalize).toList();
        kinds = new ArrayList<>(lines.size());
        stepHeaders = new ArrayList<>(lines.size());
        diagnosticLines = new ArrayList<>(lines.size());
        Set<String> printed = new HashSet<>();
        int stepHeader = -1;
        boolean commandBlock = false;
        boolean metadata = false;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            boolean run = RUN.matcher(line).find();
            boolean command = line.startsWith("##[command]");
            if (run || command) {
                printed.clear();
                stepHeader = index;
                commandBlock = run && line.startsWith("##[group]");
                metadata = false;
            }
            String instruction = RUN.matcher(line).replaceFirst("");
            if (command) instruction = line.substring("##[command]".length()).strip();
            Kind kind;
            if (line.startsWith("##[endgroup]")) {
                commandBlock = false;
                metadata = false;
                kind = Kind.STEP_METADATA;
            } else if (commandBlock && (metadata || line.startsWith("shell:") || line.startsWith("env:"))) {
                metadata = true;
                kind = Kind.STEP_METADATA;
            } else if (run || command || commandBlock || PRINT.matcher(line).find()
                    || EXPLICIT_EXIT.matcher(line).matches() || line.startsWith("+ ")) {
                kind = Kind.SHELL_COMMAND;
                printed.addAll(printedLiterals(instruction));
            } else if (printed.contains(line)) {
                kind = Kind.PRINTED_OUTPUT;
            } else if (EXIT.matcher(line).matches()) {
                kind = Kind.PROCESS_EXIT;
            } else if (DIAGNOSTIC.matcher(line).find()) {
                kind = Kind.APPLICATION_DIAGNOSTIC;
            } else {
                kind = Kind.OTHER;
            }
            kinds.add(kind);
            stepHeaders.add(stepHeader);
            diagnosticLines.add(kind == Kind.SHELL_COMMAND || kind == Kind.PRINTED_OUTPUT
                    || kind == Kind.PROCESS_EXIT || kind == Kind.STEP_METADATA ? "" : line);
        }
    }

    List<String> lines() { return lines; }
    List<String> diagnosticLines() { return diagnosticLines; }
    Kind kind(int index) { return kinds.get(index); }
    int stepHeader(int index) { return stepHeaders.get(index); }

    boolean isFailureAnchor(int index) {
        return kind(index) == Kind.PROCESS_EXIT || kind(index) == Kind.APPLICATION_DIAGNOSTIC
                || kind(index) == Kind.PRINTED_OUTPUT && DIAGNOSTIC.matcher(lines.get(index)).find()
                || EXPLICIT_EXIT.matcher(lines.get(index)).matches();
    }

    private static String normalize(String line) {
        return TIMESTAMP.matcher(ANSI.matcher(line).replaceAll("")).replaceFirst("").strip();
    }

    private static List<String> printedLiterals(String command) {
        var printer = PRINT.matcher(command);
        if (!printer.find()) return List.of();
        List<String> arguments = new ArrayList<>();
        String argumentsText = command.substring(printer.end());
        var matcher = ARGUMENT.matcher(argumentsText);
        int end = 0;
        while (matcher.find()) {
            if (!argumentsText.substring(end, matcher.start()).isBlank()) return List.of();
            end = matcher.end();
            if (matcher.group(3) != null) {
                if (Set.of(";", "&&", "||").contains(matcher.group(3))) break;
                return List.of();
            }
            String argument = matcher.group(1) != null ? matcher.group(1)
                    : matcher.group(2) != null ? matcher.group(2) : matcher.group(4);
            // Single-quoted metacharacters are literal; expansions cannot be resolved from logs alone.
            if (matcher.group(2) == null && (argument.contains("$") || argument.contains("`"))) {
                return List.of();
            }
            arguments.add(argument);
        }
        if (command.stripLeading().replaceFirst("^\\+\\s*", "").startsWith("echo")) {
            while (!arguments.isEmpty() && arguments.getFirst().matches("-[enE]+")) arguments.removeFirst();
            return String.join(" ", arguments).replace("\\n", "\n").lines().map(String::strip).toList();
        }
        if (arguments.isEmpty()) return List.of();
        String format = arguments.removeFirst();
        for (String argument : arguments) {
            int placeholder = format.indexOf("%s");
            if (placeholder < 0) return List.of();
            format = format.substring(0, placeholder) + argument + format.substring(placeholder + 2);
        }
        if (format.contains("%")) return List.of();
        return format.replace("\\n", "\n").lines().map(String::strip).toList();
    }
}
