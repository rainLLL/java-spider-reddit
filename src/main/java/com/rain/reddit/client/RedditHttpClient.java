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

@Component
public class RedditHttpClient {

    private static final Logger log = LoggerFactory.getLogger(RedditHttpClient.class);
    private final HttpClient client;
    private final CrawlerProperties properties;
    private final AtomicBoolean firstRequest = new AtomicBoolean(true);

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
        log.debug("GET {}", uri);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.readTimeout())
                .header("User-Agent", properties.userAgent())
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Reddit returned HTTP " + response.statusCode() + " for " + uri);
        }
        return response.body();
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
