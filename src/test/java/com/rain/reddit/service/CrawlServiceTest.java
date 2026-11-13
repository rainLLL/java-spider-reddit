package com.rain.reddit.service;

import com.rain.reddit.client.RedditClientErrorException;
import com.rain.reddit.client.RedditHttpClient;
import com.rain.reddit.config.CrawlerProperties;
import com.rain.reddit.parser.RedditPageParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrawlServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void exportsCollectedProfilesBeforeFatalClientError() throws Exception {
        CrawlerProperties properties = properties();
        FakeClient client = new FakeClient(properties);
        URI first = URI.create("https://www.reddit.com/user/Alice/");
        URI second = URI.create("https://www.reddit.com/user/Bob/");
        client.responder = uri -> {
            if (uri.equals(first)) return "<p data-testid='profile-description'>Engineer</p>";
            if (uri.equals(second)) throw new RedditClientErrorException(403, uri.toString());
            return "<article id='post'><a href='" + first + "'>Alice</a><a href='" + second + "'>Bob</a></article>";
        };
        VisitedUserCache cache = new VisitedUserCache(properties);
        CrawlService service = new CrawlService(properties, client, new RedditPageParser(), cache,
                new CsvExportService(properties));

        assertThatThrownBy(service::crawl).isInstanceOf(RedditClientErrorException.class);
        Path output = Files.list(properties.outputDirectory()).findFirst().orElseThrow();
        assertThat(Files.readString(output)).contains("\"Alice\",\"Engineer\"").doesNotContain("Bob");
        assertThat(cache.contains("Alice")).isTrue();
        assertThat(cache.contains("Bob")).isFalse();
    }

    @Test
    void visitsAtMostOneHundredUncachedProfiles() throws Exception {
        CrawlerProperties properties = properties();
        FakeClient client = new FakeClient(properties);
        StringBuilder listing = new StringBuilder("<article id='post'>");
        for (int i = 0; i < 101; i++) {
            listing.append("<a href='https://www.reddit.com/user/User").append(i).append("/'>user</a>");
        }
        listing.append("</article>");
        client.responder = uri -> {
            return uri.getPath().startsWith("/user/")
                    ? "<p data-testid='profile-description'>Hello</p>" : listing.toString();
        };
        CrawlService service = new CrawlService(properties, client, new RedditPageParser(),
                new VisitedUserCache(properties), new CsvExportService(properties));

        service.crawl();

        assertThat(client.calls).isEqualTo(101); // one listing and 100 profiles
        Path output = Files.list(properties.outputDirectory()).findFirst().orElseThrow();
        assertThat(Files.readString(output)).contains("User99").doesNotContain("User100");
    }

    private CrawlerProperties properties() {
        return new CrawlerProperties("test", 1, Duration.ZERO, Duration.ofSeconds(1),
                Duration.ofSeconds(1), "test-agent", tempDirectory.resolve("output"),
                tempDirectory.resolve("visited-users.txt"));
    }

    private static class FakeClient extends RedditHttpClient {
        int calls;
        Responder responder;

        FakeClient(CrawlerProperties properties) {
            super(properties);
        }

        @Override
        public String get(URI uri) throws IOException {
            calls++;
            return responder.respond(uri);
        }
    }

    @FunctionalInterface
    private interface Responder {
        String respond(URI uri) throws IOException;
    }
}
