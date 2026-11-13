package com.rain.reddit.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties(prefix = "reddit.crawler")
public record CrawlerProperties(
        String community,
        int maxListingPages,
        Duration requestDelay,
        Duration connectTimeout,
        Duration readTimeout,
        String userAgent,
        Path outputDirectory,
        Path cacheFile
) {
    public CrawlerProperties {
        if (community == null || !community.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("reddit.crawler.community must be a subreddit name");
        }
        if (maxListingPages < 1) {
            throw new IllegalArgumentException("reddit.crawler.max-listing-pages must be at least 1");
        }
        requestDelay = requestDelay == null ? Duration.ofSeconds(2) : requestDelay;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(15) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(30) : readTimeout;
        outputDirectory = outputDirectory == null ? Path.of("output") : outputDirectory;
        cacheFile = cacheFile == null ? Path.of("data", "visited-users.txt") : cacheFile;
    }
}
