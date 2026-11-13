package com.rain.reddit.client;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

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
}
