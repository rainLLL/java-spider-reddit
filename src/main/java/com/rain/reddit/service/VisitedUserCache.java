package com.rain.reddit.service;

import com.rain.reddit.config.CrawlerProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Component
public class VisitedUserCache {

    private final Path cacheFile;
    private final Set<String> visited = new HashSet<>();

    public VisitedUserCache(CrawlerProperties properties) throws IOException {
        this.cacheFile = properties.cacheFile().toAbsolutePath().normalize();
        Path parent = cacheFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(cacheFile)) {
            Files.readAllLines(cacheFile, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .map(this::normalize)
                    .forEach(visited::add);
        }
    }

    public synchronized boolean contains(String userId) {
        return visited.contains(normalize(userId));
    }

    public synchronized void markVisited(String userId) throws IOException {
        String normalized = normalize(userId);
        if (!visited.add(normalized)) {
            return;
        }
        Files.writeString(cacheFile, normalized + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private String normalize(String userId) {
        return userId.toLowerCase(Locale.ROOT);
    }
}
