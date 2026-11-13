package com.rain.reddit.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LogFileRetentionTest {

    @TempDir
    Path directory;

    @Test
    void deletesOnlyOldRunLogs() throws Exception {
        Instant now = Instant.parse("2026-11-13T12:00:00Z");
        Path expired = Files.createFile(directory.resolve("java-spider-reddit.2026-11-01-120000-123.log"));
        Path recent = Files.createFile(directory.resolve("java-spider-reddit.2026-11-13-110000-456.log"));
        Path unrelated = Files.createFile(directory.resolve("other.2026-11-01.log"));
        Files.setLastModifiedTime(expired, FileTime.from(now.minus(Duration.ofDays(6))));
        Files.setLastModifiedTime(recent, FileTime.from(now.minus(Duration.ofDays(1))));
        Files.setLastModifiedTime(unrelated, FileTime.from(now.minus(Duration.ofDays(6))));

        LogFileRetention.cleanOldLogs(directory, now);

        assertThat(expired).doesNotExist();
        assertThat(recent).exists();
        assertThat(unrelated).exists();
    }
}
