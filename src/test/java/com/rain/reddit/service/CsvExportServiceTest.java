package com.rain.reddit.service;

import com.rain.reddit.config.CrawlerProperties;
import com.rain.reddit.domain.ProfileRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvExportServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void writesUtf8CsvWithQuotedMultilineFieldsAndSkipsEmptyProfiles() throws Exception {
        CrawlerProperties properties = new CrawlerProperties(
                "test", 1, 100, Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(1),
                "test-agent", tempDirectory.resolve("output"), tempDirectory.resolve("visited-users.txt"));
        Path output = new CsvExportService(properties).export(List.of(
                new ProfileRecord("empty", "   ", List.of()),
                new ProfileRecord("alice", "你好, \"朋友\"\n第二行", List.of()),
                new ProfileRecord("bob", "", List.of("https://example.com/one", "https://example.com/two"))));

        assertThat(output.getFileName().toString()).matches("\\d{4}-\\d{2}-\\d{2}-\\d{6}\\.csv");
        assertThat(Files.readString(output, StandardCharsets.UTF_8)).isEqualTo(
                "user_id,profile_description,social_links\r\n"
                + "\"alice\",\"你好, \"\"朋友\"\"\n第二行\",\"\"\r\n"
                + "\"bob\",\"\",\"https://example.com/one\nhttps://example.com/two\"\r\n");
    }
}
