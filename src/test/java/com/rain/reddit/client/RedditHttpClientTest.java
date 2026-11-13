package com.rain.reddit.client;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpHeaders;
import javax.net.ssl.SSLSession;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import java.nio.file.Path;

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
        CrawlerProperties properties = new CrawlerProperties("test", 1, Duration.ZERO,
                Duration.ofSeconds(1), Duration.ofSeconds(1), "test-agent", Path.of("output"), Path.of("cache"));

        assertThatThrownBy(() -> new RedditHttpClient(properties, (request, handler) -> {
            calls.incrementAndGet();
            return forbidden;
        }).get(uri))
                .isInstanceOf(RedditClientErrorException.class)
                .hasMessageContaining("HTTP 403");
        assertThat(calls.get()).isEqualTo(4);
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
