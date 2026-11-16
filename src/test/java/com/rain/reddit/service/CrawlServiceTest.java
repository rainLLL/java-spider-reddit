package com.rain.reddit.service;

import com.rain.reddit.client.RedditClientErrorException;
import com.rain.reddit.client.RedditHttpClient;
import com.rain.reddit.config.CrawlerProperties;
import com.rain.reddit.parser.RedditPageParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrawlServiceTest {
    @TempDir Path tempDirectory;

    @Test
    void handlesPostsAndTheirCommentsInOrderBeforeFetchingAnotherListing() throws Exception {
        CrawlerProperties properties = properties(4);
        FakeClient client = new FakeClient(properties);
        client.responder = uri -> {
            if (uri.getPath().equals("/r/test/new/")) return post("Alice", "1", 1)
                    + post("Bob", "2", 1) + next("/svc/shreddit/community-more-posts/new/?after=later");
            if (uri.getPath().contains("/comments/1/")) return "<a href='/user/Carol/'>Carol</a>";
            if (uri.getPath().contains("/comments/2/")) return "<a href='/user/Dave/'>Dave</a>";
            return profileOrEmpty(uri);
        };

        service(properties, client, new VisitedUserCache(properties)).crawl();

        assertThat(client.gets).containsSubsequence("/r/test/", "/r/test/new/",
                "/r/test/comments/1/title/", "/r/test/comments/2/title/",
                "/user/Alice/", "/user/Carol/", "/user/Bob/", "/user/Dave/");
        assertThat(client.gets).noneMatch(path -> path.startsWith("/svc/shreddit/community-more-posts/"));
        assertThat(client.requestedUris.stream().filter(uri -> uri.getPath().equals("/r/test/new/"))
                .findFirst().orElseThrow().getQuery())
                .isEqualTo("screen_view_count=1&ext-referrer=DIRECT");
        assertThat(Files.readString(Files.list(properties.outputDirectory()).findFirst().orElseThrow()))
                .containsSubsequence("Alice", "Carol", "Bob", "Dave");
    }

    @Test
    void stopsCommentPaginationAndLaterPostsAtConfiguredCount() throws Exception {
        CrawlerProperties properties = properties(3);
        FakeClient client = new FakeClient(properties);
        client.responder = uri -> {
            if (uri.getPath().equals("/r/test/new/")) return post("Alice", "1", 1) + post("Later", "2", 1);
            if (uri.getPath().contains("/comments/1/")) return """
                    <a href='/user/Bob/'>Bob</a>
                    <faceplate-partial id='top-level-more-comments-partial' method='post'
                      src='/svc/shreddit/more-comments/test/t3_1?top-level=1'>
                      <input name='cursor' value='first'>
                    </faceplate-partial>
                    """;
            return profileOrEmpty(uri);
        };
        client.postResponder = (uri, cursor) -> """
                <a href='/user/Carol/'>Carol</a>
                <faceplate-partial class='more-comments-partial' method='post'
                  src='/svc/shreddit/more-comments/test/t3_1?rootCommentOffset=8'>
                  <input name='cursor' value='second'>
                </faceplate-partial>
                """;

        service(properties, client, new VisitedUserCache(properties)).crawl();

        assertThat(client.posts).containsExactly("/svc/shreddit/more-comments/test/t3_1?top-level=1 cursor=first");
        assertThat(client.gets).doesNotContain("/r/test/comments/2/title/");
        assertThat(client.profileCalls).isEqualTo(3);
    }

    @Test
    void followsNewListingPaginationOnlyWhenMoreProfilesAreNeeded() throws Exception {
        CrawlerProperties properties = properties(2);
        FakeClient client = new FakeClient(properties);
        client.responder = uri -> {
            if (uri.getPath().equals("/r/test/new/")) return post("Alice", "1", 0)
                    + next("/svc/shreddit/community-more-posts/new/?after=second");
            if (uri.getPath().equals("/svc/shreddit/community-more-posts/new/")) return post("Bob", "2", 0);
            return profileOrEmpty(uri);
        };

        service(properties, client, new VisitedUserCache(properties)).crawl();

        assertThat(client.gets).containsSubsequence("/r/test/new/", "/svc/shreddit/community-more-posts/new/");
        assertThat(client.requestedUris.stream()
                .filter(uri -> uri.getPath().equals("/svc/shreddit/community-more-posts/new/"))
                .findFirst().orElseThrow().getQuery()).isEqualTo("after=second");
        assertThat(client.profileCalls).isEqualTo(2);
    }

    @Test
    void continuesThroughMultipleListingPagesUntilConfiguredProfileCount() throws Exception {
        CrawlerProperties properties = properties(3);
        FakeClient client = new FakeClient(properties);
        client.responder = uri -> {
            if (uri.getPath().equals("/r/test/new/")) return post("Alice", "1", 0)
                    + next("/svc/shreddit/community-more-posts/new/?after=second");
            if (uri.getPath().equals("/svc/shreddit/community-more-posts/new/")) {
                if ("after=second".equals(uri.getQuery())) return post("Bob", "2", 0)
                        + next("/svc/shreddit/community-more-posts/new/?after=third");
                return post("Carol", "3", 0)
                        + next("/svc/shreddit/community-more-posts/new/?after=fourth");
            }
            return profileOrEmpty(uri);
        };

        service(properties, client, new VisitedUserCache(properties)).crawl();

        assertThat(client.requestedUris.stream()
                .filter(uri -> uri.getPath().equals("/svc/shreddit/community-more-posts/new/"))
                .map(URI::getQuery)).containsExactly("after=second", "after=third");
        assertThat(client.profileCalls).isEqualTo(3);
    }

    @Test
    void exportsCollectedProfilesBeforeFatalClientError() throws Exception {
        CrawlerProperties properties = properties(2);
        FakeClient client = new FakeClient(properties);
        client.responder = uri -> {
            if (uri.getPath().equals("/r/test/new/")) return post("Alice", "1", 0) + post("Bob", "2", 0);
            if (uri.getPath().equals("/user/Bob/")) throw new RedditClientErrorException(403, uri.toString());
            return profileOrEmpty(uri);
        };
        VisitedUserCache cache = new VisitedUserCache(properties);

        assertThatThrownBy(() -> service(properties, client, cache).crawl())
                .isInstanceOf(RedditClientErrorException.class);
        assertThat(Files.readString(Files.list(properties.outputDirectory()).findFirst().orElseThrow()))
                .contains("Alice").doesNotContain("Bob");
        assertThat(cache.contains("Alice")).isTrue();
        assertThat(cache.contains("Bob")).isFalse();
    }

    @Test
    void usesConfiguredCountAboveOneHundred() throws Exception {
        CrawlerProperties properties = properties(101);
        FakeClient client = new FakeClient(properties);
        client.responder = uri -> {
            if (uri.getPath().equals("/r/test/new/")) {
                StringBuilder html = new StringBuilder();
                for (int i = 0; i < 102; i++) html.append(post("User" + i, "p" + i, 0));
                return html.toString();
            }
            return profileOrEmpty(uri);
        };

        service(properties, client, new VisitedUserCache(properties)).crawl();

        assertThat(client.profileCalls).isEqualTo(101);
    }

    @Test
    void solvesInitialChallengeBeforeRequestingTimeSortedListing() throws Exception {
        CrawlerProperties properties = properties(1);
        FakeClient client = new FakeClient(properties);
        client.responder = uri -> {
            if (uri.getPath().equals("/r/test/") && uri.getQuery() == null) return """
                    <input name='js_challenge' value='1'>
                    <input name='jsc_token' value='token'>
                    <input name='jsc_orig_r' value=''>
                    <script>var n=(await(async e=>e+e)("seed"));</script>
                    """;
            if (uri.getPath().equals("/r/test/new/")) return post("Alice", "1", 0);
            return profileOrEmpty(uri);
        };

        service(properties, client, new VisitedUserCache(properties)).crawl();

        assertThat(client.requestedUris).hasSize(4);
        assertThat(client.requestedUris.get(1).getQuery()).contains("solution=seedseed", "jsc_token=token");
        assertThat(client.requestedUris.get(2).getPath()).isEqualTo("/r/test/new/");
    }

    @Test
    void stillReadsCommentsWhenPostAuthorIsCached() throws Exception {
        CrawlerProperties properties = properties(1);
        FakeClient client = new FakeClient(properties);
        VisitedUserCache cache = new VisitedUserCache(properties);
        cache.markVisited("Alice");
        client.responder = uri -> {
            if (uri.getPath().equals("/r/test/new/")) return post("Alice", "1", 1);
            if (uri.getPath().contains("/comments/1/")) return "<a href='/user/Bob/'>Bob</a>";
            return profileOrEmpty(uri);
        };

        service(properties, client, cache).crawl();

        assertThat(client.gets).contains("/r/test/comments/1/title/", "/user/Bob/")
                .doesNotContain("/user/Alice/");
        assertThat(client.profileCalls).isEqualTo(1);
    }

    private String profileOrEmpty(URI uri) {
        return uri.getPath().startsWith("/user/")
                ? "<p data-testid='profile-description'>Instagram</p>" : "";
    }

    private String post(String author, String id, int comments) {
        return "<article id='post-" + id + "'><a aria-label='Author: u/" + author
                + "' href='/user/" + author + "/'>author</a><shreddit-post permalink='/r/test/comments/"
                + id + "/title/' comment-count='" + comments + "'></shreddit-post></article>";
    }

    private String next(String src) {
        return "<faceplate-partial slot='load-after' src='" + src + "'></faceplate-partial>";
    }

    private CrawlerProperties properties(int maxProfileRequests) {
        return new CrawlerProperties("test", maxProfileRequests, Duration.ZERO,
                Duration.ofSeconds(1), Duration.ofSeconds(1), "test-agent",
                tempDirectory.resolve("output"), tempDirectory.resolve("visited-users.txt"));
    }

    private CrawlService service(CrawlerProperties properties, FakeClient client, VisitedUserCache cache) {
        return new CrawlService(properties, client, new RedditPageParser(), cache, new CsvExportService(properties));
    }

    private static class FakeClient extends RedditHttpClient {
        int profileCalls;
        List<String> gets = new ArrayList<>();
        List<URI> requestedUris = new ArrayList<>();
        List<String> posts = new ArrayList<>();
        Responder responder;
        PostResponder postResponder;

        FakeClient(CrawlerProperties properties) { super(properties); }

        @Override public String get(URI uri) throws IOException {
            gets.add(uri.getPath());
            requestedUris.add(uri);
            if (uri.getPath().startsWith("/user/")) profileCalls++;
            return responder.respond(uri);
        }

        @Override public String getListing(URI uri) throws IOException {
            return get(uri);
        }

        @Override public String postComments(URI uri, String cursor) throws IOException {
            posts.add(uri.getPath() + "?" + uri.getQuery() + " cursor=" + cursor);
            return postResponder.respond(uri, cursor);
        }
    }

    @FunctionalInterface private interface Responder {
        String respond(URI uri) throws IOException;
    }

    @FunctionalInterface private interface PostResponder {
        String respond(URI uri, String cursor) throws IOException;
    }
}
