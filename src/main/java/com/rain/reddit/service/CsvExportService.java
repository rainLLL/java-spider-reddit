package com.rain.reddit.service;

import com.rain.reddit.config.CrawlerProperties;
import com.rain.reddit.domain.ProfileRecord;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class CsvExportService {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
    private final Path outputDirectory;

    public CsvExportService(CrawlerProperties properties) {
        this.outputDirectory = properties.outputDirectory().toAbsolutePath().normalize();
    }

    public Path export(List<ProfileRecord> profiles) throws IOException {
        Files.createDirectories(outputDirectory);
        Path output = outputDirectory.resolve(FILE_TIME.format(LocalDateTime.now()) + ".csv");
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("user_id,profile_description,social_links\r\n");
            for (ProfileRecord profile : profiles) {
                if (!profile.hasExportableContent()) {
                    continue;
                }
                writer.write(csvField(profile.userId()) + "," + csvField(profile.description()) + ","
                        + csvField(String.join("\n", profile.socialLinks())) + "\r\n");
            }
        }
        return output;
    }

    private static String csvField(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
