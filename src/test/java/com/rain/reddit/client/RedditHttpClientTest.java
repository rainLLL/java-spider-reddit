package com.rain.reddit.client;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpHeaders;
import javax.net.ssl.SSLSession;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import com.rain.reddit.config.CrawlerProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedditHttpClientTest {

    @Test
    void buildsReplayableCurlAndRedactsChallengeQueryValues() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "https://www.reddit.com/r/test/?solution=secret&jsc_token=token&safe=value"))
                .header("User-Agent", "test-agent")
                .header("Accept", "text/html")
                .GET()
                .build();

        String curl = RedditHttpClient.buildCurl(request);

        assertThat(curl)
                .contains("curl --request GET")
                .contains("--header 'User-Agent: test-agent'")
                .contains("solution=[REDACTED]")
                .contains("jsc_token=[REDACTED]")
                .contains("safe=value")
                .doesNotContain("solution=secret")
                .doesNotContain("jsc_token=token");
    }

    @Test
    void redactsChallengeInputsButKeepsOtherResponseContent() {
        String body = """
                <input type="hidden" name="jsc_token" value="secret-token"/>
                <p data-testid="profile-description">Public profile</p>
                """;

        String sanitized = RedditHttpClient.sanitizeResponseBody(body);

        assertThat(sanitized)
                .contains("name=\"jsc_token\" value=\"[REDACTED]\"")
                .contains("Public profile")
                .doesNotContain("secret-token");
    }

    @Test
    void logsAtMostTwoThousandCharactersAfterRedaction() {
        String body = "<input name=\"jsc_token\" value=\"secret\">" + "文".repeat(2100);

        String logged = RedditHttpClient.responseBodyForLog(body);

        assertThat(logged).hasSize(2000).contains("[REDACTED]").doesNotContain("secret");
        assertThat(RedditHttpClient.responseBodyForLog("short")).isEqualTo("short");
    }

    @Test
    void retriesClientErrorsThreeTimesThenStops() throws Exception {
        URI uri = URI.create("https://www.reddit.com/r/test/");
        AtomicInteger calls = new AtomicInteger();
        HttpResponse<String> forbidden = response(uri, 403);
        CrawlerProperties properties = new CrawlerProperties("test", 1, 100, Duration.ZERO,
                Duration.ofSeconds(1), Duration.ofSeconds(1), "test-agent", Path.of("output"), Path.of("cache"));

        assertThatThrownBy(() -> new RedditHttpClient(properties, (request, handler) -> {
            calls.incrementAndGet();
            return forbidden;
        }).get(uri))
                .isInstanceOf(RedditClientErrorException.class)
                .hasMessageContaining("HTTP 403");
        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    void addsFormContentTypeOnlyForTimeSortedListing() throws Exception {
        URI uri = URI.create("https://www.reddit.com/r/test/new/?screen_view_count=1&ext-referrer=DIRECT");
        CrawlerProperties properties = new CrawlerProperties("test", 1, 100, Duration.ZERO,
                Duration.ofSeconds(1), Duration.ofSeconds(1), "test-agent", Path.of("output"), Path.of("cache"));
        var requests = new java.util.ArrayList<HttpRequest>();
        RedditHttpClient client = new RedditHttpClient(properties, (request, handler) -> {
            requests.add(request);
            return response(uri, 200);
        });

        client.getListing(uri);
        client.get(uri);

        assertThat(requests.get(0).headers().firstValue("Content-Type"))
                .contains("application/x-www-form-urlencoded");
        assertThat(requests.get(1).headers().firstValue("Content-Type")).isEmpty();
    }

    @Test
    void postsEncodedCursorAndCookieCsrfWithoutLoggingTheirValues() throws Exception {
        URI uri = URI.create("https://www.reddit.com/svc/shreddit/more-comments/test/t3_123?top-level=1");
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        cookies.getCookieStore().add(uri, new HttpCookie("csrf_token", "csrf+secret"));
        CrawlerProperties properties = new CrawlerProperties("test", 1, 100, Duration.ZERO,
                Duration.ofSeconds(1), Duration.ofSeconds(1), "test-agent", Path.of("output"), Path.of("cache"));
        var requests = new java.util.ArrayList<HttpRequest>();
        RedditHttpClient client = new RedditHttpClient(properties, (request, handler) -> {
            requests.add(request);
            return response(uri, 200);
        }, cookies);

        client.postComments(uri, "cursor/value+=");

        HttpRequest request = requests.get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.headers().firstValue("Content-Type")).contains("application/x-www-form-urlencoded");
        assertThat(body(request)).isEqualTo("cursor=cursor%2Fvalue%2B%3D&csrf_token=csrf%2Bsecret");
        assertThat(RedditHttpClient.buildCurl(request))
                .contains("cursor=[REDACTED]&csrf_token=[REDACTED]")
                .doesNotContain("cursor/value", "csrf+secret");
    }

    private String body(HttpRequest request) throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            private final StringBuilder value = new StringBuilder();
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer item) { value.append(StandardCharsets.UTF_8.decode(item)); }
            public void onError(Throwable throwable) { result.completeExceptionally(throwable); }
            public void onComplete() { result.complete(value.toString()); }
        });
        return result.get();
    }

    private HttpResponse<String> response(URI uri, int status) {
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public HttpRequest request() { return null; }
            public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(java.util.Map.of(), (key, value) -> true); }
            public String body() { return "blocked"; }
            public Optional<SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return uri; }
            public java.net.http.HttpClient.Version version() { return java.net.http.HttpClient.Version.HTTP_1_1; }
        };
    }
}
