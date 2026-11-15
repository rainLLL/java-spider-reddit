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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrawlServiceTest {

    @TempDir
    Path tempDirectory;

    @Test
    void exportsCollectedProfilesBeforeFatalClientError() throws Exception {
        CrawlerProperties properties = properties(100);
        FakeClient client = new FakeClient(properties);
        URI first = URI.create("https://www.reddit.com/user/Alice/");
        URI second = URI.create("https://www.reddit.com/user/Bob/");
        client.responder = uri -> {
            if (uri.equals(first)) return "<p data-testid='profile-description'>Engineer on Instagram</p>";
            if (uri.equals(second)) throw new RedditClientErrorException(403, uri.toString());
            return "<article id='post'><a href='" + first + "'>Alice</a><a href='" + second + "'>Bob</a></article>";
        };
        VisitedUserCache cache = new VisitedUserCache(properties);
        CrawlService service = new CrawlService(properties, client, new RedditPageParser(), cache,
                new CsvExportService(properties));

        assertThatThrownBy(service::crawl).isInstanceOf(RedditClientErrorException.class);
        Path output = Files.list(properties.outputDirectory()).findFirst().orElseThrow();
        assertThat(Files.readString(output)).contains("\"Alice\",\"Engineer on Instagram\"").doesNotContain("Bob");
        assertThat(cache.contains("Alice")).isTrue();
        assertThat(cache.contains("Bob")).isFalse();
    }

    @Test
    void respectsConfiguredProfileRequestLimit() throws Exception {
        CrawlerProperties properties = properties(2);
        FakeClient client = new FakeClient(properties);
        StringBuilder listing = new StringBuilder("<article id='post'>");
        for (int i = 0; i < 101; i++) {
            listing.append("<a href='https://www.reddit.com/user/User").append(i).append("/'>user</a>");
        }
        listing.append("</article>");
        client.responder = uri -> {
            return uri.getPath().startsWith("/user/")
                    ? "<p data-testid='profile-description'>Hello on discord</p>" : listing.toString();
        };
        CrawlService service = new CrawlService(properties, client, new RedditPageParser(),
                new VisitedUserCache(properties), new CsvExportService(properties));

        service.crawl();

        assertThat(client.calls).isEqualTo(3); // one listing and two profiles
        Path output = Files.list(properties.outputDirectory()).findFirst().orElseThrow();
        assertThat(Files.readString(output)).contains("User1").doesNotContain("User2");
    }

    @Test
    void stopsCommentContinuationAfterFindingOneHundredProfiles() throws Exception {
        CrawlerProperties properties = properties(100);
        FakeClient client = new FakeClient(properties);
        URI comments = URI.create("https://www.reddit.com/r/test/comments/123/title/");
        URI more = URI.create("https://www.reddit.com/svc/shreddit/more-comments/test/t3_123?top-level=1");
        URI next = URI.create("https://www.reddit.com/svc/shreddit/more-comments/test/t3_123?rootCommentOffset=8");
        client.responder = uri -> {
            if (uri.getPath().startsWith("/user/")) return "<p data-testid='profile-description'>Find me on Instagram</p>";
            if (uri.getPath().startsWith("/r/test/comments/")) return """
                    <a class='truncate font-bold text-neutral-content-strong text-12'
                       href='/user/User0/'>User0</a>
                    <faceplate-partial id='top-level-more-comments-partial' method='post'
                       src='/svc/shreddit/more-comments/test/t3_123?top-level=1'>
                      <input name='cursor' value='first'>
                    </faceplate-partial>
                    """;
            return "<article id='post'><shreddit-post permalink='" + comments.getPath()
                    + "' comment-count='1'></shreddit-post></article>";
        };
        client.postResponder = (uri, cursor) -> {
            if (uri.equals(more)) {
                StringBuilder html = new StringBuilder();
                for (int i = 1; i <= 100; i++) {
                    html.append("<a href='/user/User").append(i).append("/'>user</a>");
                }
                html.append("<faceplate-partial class='more-comments-partial' method='post' src='")
                        .append(next).append("'><input name='cursor' value='second'></faceplate-partial>");
                return html.toString();
            }
            return "<a href='/user/User101/'>user</a>";
        };
        CrawlService service = new CrawlService(properties, client, new RedditPageParser(),
                new VisitedUserCache(properties), new CsvExportService(properties));

        service.crawl();

        assertThat(client.posts).containsExactly(more + " cursor=first");
        assertThat(client.profileCalls).isEqualTo(100);
    }

    @Test
    void countsOnlyDistinctUnvisitedUsersBeforeRequestingNextCommentPage() throws Exception {
        CrawlerProperties properties = properties(3);
        FakeClient client = new FakeClient(properties);
        URI comments = URI.create("https://www.reddit.com/r/test/comments/123/title/");
        URI more = URI.create("https://www.reddit.com/svc/shreddit/more-comments/test/t3_123?top-level=1");
        URI next = URI.create("https://www.reddit.com/svc/shreddit/more-comments/test/t3_123?rootCommentOffset=8");
        VisitedUserCache cache = new VisitedUserCache(properties);
        cache.markVisited("Existing");
        client.responder = uri -> {
            if (uri.getPath().startsWith("/user/")) return "<p data-testid='profile-description'>Instagram</p>";
            if (uri.getPath().startsWith("/r/test/comments/")) return """
                    <a href='/user/Existing/'>cached</a><a href='/user/New1/'>new</a>
                    <faceplate-partial id='top-level-more-comments-partial' method='post'
                      src='/svc/shreddit/more-comments/test/t3_123?top-level=1'>
                      <input name='cursor' value='first'>
                    </faceplate-partial>
                    """;
            return "<article id='post'><shreddit-post permalink='" + comments.getPath()
                    + "' comment-count='1'></shreddit-post></article>";
        };
        client.postResponder = (uri, cursor) -> {
            if (uri.equals(more)) return """
                    <a href='/user/new1/'>duplicate</a><a href='/user/New2/'>new</a>
                    <faceplate-partial class='more-comments-partial' method='post'
                      src='/svc/shreddit/more-comments/test/t3_123?rootCommentOffset=8'>
                      <input name='cursor' value='second'>
                    </faceplate-partial>
                    """;
            if (uri.equals(next)) return """
                    <a href='/user/New3/'>new</a>
                    <faceplate-partial class='more-comments-partial' method='post'
                      src='/svc/shreddit/more-comments/test/t3_123?rootCommentOffset=9'>
                      <input name='cursor' value='third'>
                    </faceplate-partial>
                    """;
            throw new AssertionError("Unexpected extra comment request: " + uri);
        };
        CrawlService service = new CrawlService(properties, client, new RedditPageParser(), cache,
                new CsvExportService(properties));

        service.crawl();

        assertThat(client.posts).containsExactly(more + " cursor=first", next + " cursor=second");
        assertThat(client.profileCalls).isEqualTo(3);
    }

    @Test
    void allowsConfiguredProfileRequestLimitAboveOneHundred() throws Exception {
        CrawlerProperties properties = properties(101);
        FakeClient client = new FakeClient(properties);
        StringBuilder listing = new StringBuilder();
        for (int i = 0; i < 102; i++) {
            listing.append("<a href='/user/User").append(i).append("/'>user</a>");
        }
        client.responder = uri -> uri.getPath().startsWith("/user/")
                ? "<p data-testid='profile-description'>Instagram</p>" : listing.toString();
        CrawlService service = new CrawlService(properties, client, new RedditPageParser(),
                new VisitedUserCache(properties), new CsvExportService(properties));

        service.crawl();

        assertThat(client.profileCalls).isEqualTo(101);
    }

    private CrawlerProperties properties(int maxProfileRequests) {
        return new CrawlerProperties("test", 1, maxProfileRequests, Duration.ZERO, Duration.ofSeconds(1),
                Duration.ofSeconds(1), "test-agent", tempDirectory.resolve("output"),
                tempDirectory.resolve("visited-users.txt"));
    }

    private static class FakeClient extends RedditHttpClient {
        int calls;
        int profileCalls;
        List<String> posts = new ArrayList<>();
        Responder responder;
        PostResponder postResponder;

        FakeClient(CrawlerProperties properties) {
            super(properties);
        }

        @Override
        public String get(URI uri) throws IOException {
            calls++;
            if (uri.getPath().startsWith("/user/")) profileCalls++;
            return responder.respond(uri);
        }

        @Override
        public String postComments(URI uri, String cursor) throws IOException {
            posts.add(uri + " cursor=" + cursor);
            return postResponder.respond(uri, cursor);
        }
    }

    @FunctionalInterface
    private interface Responder {
        String respond(URI uri) throws IOException;
    }

    @FunctionalInterface
    private interface PostResponder {
        String respond(URI uri, String cursor) throws IOException;
    }
}
