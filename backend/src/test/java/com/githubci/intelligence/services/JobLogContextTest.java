package com.githubci.intelligence.services;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JobLogContextTest {
    @Test
    void distinguishesRunnerScriptPrintedTextStatusAndRuntimeExceptionWithoutChangingLineNumbers() {
        var context = new JobLogContext("""
                2026-10-21T19:45:42.1036000Z ##[group]Run printf '%s\\n' 'psql: could not connect'
                2026-10-21T19:45:42.1037855Z printf '%s\\n' 'psql: could not connect'
                2026-10-21T19:45:42.1039223Z exit 1
                shell: /usr/bin/bash -e {0}
                env:
                  SETTING: value
                ##[endgroup]
                psql: could not connect
                ##[error]Process completed with exit code 1.
                ##[group]Run mvn verify
                mvn verify
                ##[endgroup]
                org.postgresql.util.PSQLException: Connection to db:5432 refused.
                """);
        assertEquals(JobLogContext.Kind.SHELL_COMMAND, context.kind(0));
        assertEquals(JobLogContext.Kind.SHELL_COMMAND, context.kind(1));
        assertEquals(JobLogContext.Kind.SHELL_COMMAND, context.kind(2));
        assertEquals(JobLogContext.Kind.STEP_METADATA, context.kind(3));
        assertEquals(JobLogContext.Kind.STEP_METADATA, context.kind(5));
        assertEquals(JobLogContext.Kind.PRINTED_OUTPUT, context.kind(7));
        assertEquals(JobLogContext.Kind.PROCESS_EXIT, context.kind(8));
        assertEquals(JobLogContext.Kind.APPLICATION_DIAGNOSTIC, context.kind(12));
        assertEquals(0, context.stepHeader(7));
        assertEquals(9, context.stepHeader(12));
        assertEquals("", context.diagnosticLines().get(7));
        assertEquals("org.postgresql.util.PSQLException: Connection to db:5432 refused.",
                context.diagnosticLines().get(12));
    }

    @Test
    void exceptionNamesInDisplayedShellSourceAreNotRuntimeExceptions() {
        var context = new JobLogContext("""
                ##[group]Run python example.py
                python -c 'raise RuntimeError("org.postgresql.util.PSQLException: Connection refused")'
                ##[endgroup]
                """);
        assertEquals(JobLogContext.Kind.SHELL_COMMAND, context.kind(1));
        assertTrue(context.diagnosticLines().stream().allMatch(String::isEmpty));
    }

    @Test
    void preservesAnExplicitEmptyLastLineInAlreadySplitLogs() {
        var context = new JobLogContext(List.of("output", ""));
        assertEquals(2, context.lines().size());
        assertEquals(JobLogContext.Kind.OTHER, context.kind(1));
    }
}
