package com.rain.reddit.client;

import com.rain.reddit.config.CrawlerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

@Component
public class RedditHttpClient {

    private static final Logger log = LoggerFactory.getLogger(RedditHttpClient.class);
    private static final int MAX_LOGGED_RESPONSE_CHARS = 2000;
    private static final Pattern SENSITIVE_QUERY_VALUE = Pattern.compile(
            "(?i)([?&](?:solution|js_challenge|jsc_token|jsc_orig_r)=)[^&]*");
    private static final Pattern SENSITIVE_INPUT_VALUE = Pattern.compile(
            "(?is)(<input\\b(?=[^>]*\\bname\\s*=\\s*[\"'](?:js_challenge|jsc_token|jsc_orig_r)[\"'])"
                    + "[^>]*\\bvalue\\s*=\\s*[\"'])[^\"']*([\"'])");
    private final HttpClient client;
    private final CrawlerProperties properties;
    private final AtomicBoolean firstRequest = new AtomicBoolean(true);
    private final AtomicLong requestSequence = new AtomicLong();

    public RedditHttpClient(CrawlerProperties properties) {
        this.properties = properties;
        CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        this.client = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String get(URI uri) throws IOException, InterruptedException {
        verifyRedditUri(uri);
        throttle();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.readTimeout())
                .header("User-Agent", properties.userAgent())
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET()
                .build();
        long requestId = requestSequence.incrementAndGet();
        log.info("HTTP request #{} curl: {}", requestId, buildCurl(request));

        long startedAt = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException | RuntimeException exception) {
            log.warn("HTTP request #{} failed after {} ms: uri={}, error={}", requestId,
                    elapsedMillis(startedAt), sanitizeUri(uri), exception.toString());
            throw exception;
        }

        long durationMillis = elapsedMillis(startedAt);
        log.info("HTTP response #{}: status={}, durationMs={}, uri={}", requestId,
                response.statusCode(), durationMillis, sanitizeUri(response.uri()));
        log.debug("HTTP response body #{}:\n{}", requestId, responseBodyForLog(response.body()));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Reddit returned HTTP " + response.statusCode() + " for " + sanitizeUri(uri));
        }
        return response.body();
    }

    static String buildCurl(HttpRequest request) {
        StringBuilder curl = new StringBuilder("curl --request ")
                .append(request.method())
                .append(" --url ")
                .append(shellQuote(sanitizeUri(request.uri())));
        request.headers().map().forEach((name, values) -> values.forEach(value -> curl
                .append(" --header ")
                .append(shellQuote(name + ": " + value))));
        return curl.toString();
    }

    static String sanitizeResponseBody(String body) {
        if (body == null) {
            return "";
        }
        return SENSITIVE_INPUT_VALUE.matcher(body).replaceAll("$1[REDACTED]$2");
    }

    static String responseBodyForLog(String body) {
        String sanitized = sanitizeResponseBody(body);
        return sanitized.substring(0, Math.min(sanitized.length(), MAX_LOGGED_RESPONSE_CHARS));
    }

    private static String sanitizeUri(URI uri) {
        return SENSITIVE_QUERY_VALUE.matcher(uri.toString()).replaceAll("$1[REDACTED]");
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private void throttle() throws InterruptedException {
        if (!firstRequest.getAndSet(false)) {
            Duration delay = properties.requestDelay();
            if (!delay.isNegative() && !delay.isZero()) {
                Thread.sleep(delay.toMillis());
            }
        }
    }

    private void verifyRedditUri(URI uri) {
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || host == null
                || !(host.equalsIgnoreCase("www.reddit.com") || host.equalsIgnoreCase("reddit.com"))) {
            throw new IllegalArgumentException("Refusing non-Reddit URL: " + uri);
        }
    }
}
