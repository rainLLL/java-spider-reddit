package com.rain.reddit.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class LogFileRetention {

    private static final Pattern RUN_LOG = Pattern.compile(
            "java-spider-reddit\\.\\d{4}-\\d{2}-\\d{2}-\\d{6}(?:-\\d{3})?\\.log");
    private static final Duration RETENTION = Duration.ofDays(5);

    private LogFileRetention() {
    }

    public static void cleanOldLogs() throws IOException {
        String configured = System.getenv("LOG_DIR");
        cleanOldLogs(Path.of(configured == null || configured.isBlank() ? "logs" : configured), Instant.now());
    }

    static void cleanOldLogs(Path directory, Instant now) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        Instant cutoff = now.minus(RETENTION);
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path entry : entries.toList()) {
                if (Files.isRegularFile(entry)
                        && RUN_LOG.matcher(entry.getFileName().toString()).matches()
                        && Files.getLastModifiedTime(entry).toInstant().isBefore(cutoff)) {
                    Files.delete(entry);
                }
            }
        }
    }
}
