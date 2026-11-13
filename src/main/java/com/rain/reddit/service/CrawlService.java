package com.rain.reddit.service;

import com.rain.reddit.client.RedditHttpClient;
import com.rain.reddit.client.RedditClientErrorException;
import com.rain.reddit.config.CrawlerProperties;
import com.rain.reddit.domain.Challenge;
import com.rain.reddit.domain.ListingPage;
import com.rain.reddit.domain.ProfileRecord;
import com.rain.reddit.parser.RedditPageParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class CrawlService {

    private static final Logger log = LoggerFactory.getLogger(CrawlService.class);
    private static final URI BASE_URI = URI.create("https://www.reddit.com");
    private static final int MAX_PROFILE_REQUESTS = 100;
    private final CrawlerProperties properties;
    private final RedditHttpClient httpClient;
    private final RedditPageParser parser;
    private final VisitedUserCache cache;
    private final CsvExportService csvExportService;

    public CrawlService(CrawlerProperties properties, RedditHttpClient httpClient, RedditPageParser parser,
                        VisitedUserCache cache, CsvExportService csvExportService) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.parser = parser;
        this.cache = cache;
        this.csvExportService = csvExportService;
    }

    public void crawl() throws IOException, InterruptedException {
        URI communityUri = BASE_URI.resolve("/r/" + properties.community() + "/");
        String firstHtml = httpClient.get(communityUri);
        Challenge challenge = parser.parseChallenge(firstHtml).orElse(null);
        if (challenge != null) {
            log.info("Reddit JavaScript challenge detected; submitting documented solution");
            firstHtml = httpClient.get(withChallengeQuery(communityUri, challenge));
        }

        Set<URI> profileUrls = new LinkedHashSet<>();
        Set<URI> commentUrls = new LinkedHashSet<>();
        Set<URI> seenListingUrls = new LinkedHashSet<>();
        URI currentUri = communityUri;
        String currentHtml = firstHtml;

        for (int page = 1; page <= properties.maxListingPages() && currentHtml != null; page++) {
            if (!seenListingUrls.add(currentUri)) {
                log.warn("Pagination loop detected at {}", currentUri);
                break;
            }
            ListingPage listing = parser.parseListing(currentHtml, currentUri);
            profileUrls.addAll(listing.profileUrls());
            commentUrls.addAll(listing.commentUrls());
            log.info("Listing page {}: {} profiles, {} comment pages", page,
                    listing.profileUrls().size(), listing.commentUrls().size());
            currentUri = listing.nextPageUrl();
            currentHtml = currentUri == null || page == properties.maxListingPages() ? null : safeGet(currentUri);
        }

        log.info("Starting comments pages: {} URLs to visit", commentUrls.size());
        for (URI commentUri : commentUrls) {
            String html = safeGet(addExternalReferrer(commentUri));
            if (html != null) {
                profileUrls.addAll(parser.parseCommentProfiles(html, commentUri));
            }
        }

        Map<String, URI> pendingProfiles = new LinkedHashMap<>();
        for (URI profileUri : profileUrls) {
            String userId = parser.userIdFromUri(profileUri).orElse(null);
            if (userId != null && !cache.contains(userId)) {
                pendingProfiles.putIfAbsent(userId.toLowerCase(Locale.ROOT), profileUri);
                if (pendingProfiles.size() == MAX_PROFILE_REQUESTS) {
                    break;
                }
            }
        }
        log.info("Starting user profiles: {} URLs to visit (limit {})", pendingProfiles.size(), MAX_PROFILE_REQUESTS);

        List<ProfileRecord> profiles = new ArrayList<>();
        for (URI profileUri : pendingProfiles.values()) {
            String html;
            try {
                html = safeGet(profileUri);
            } catch (RedditClientErrorException exception) {
                if (!profiles.isEmpty()) {
                    try {
                        exportAndMarkVisited(profiles);
                    } catch (IOException exportException) {
                        exception.addSuppressed(exportException);
                    }
                }
                throw exception;
            }
            if (html == null) {
                continue;
            }
            try {
                ProfileRecord profile = parser.parseProfile(html, profileUri);
                if (profile.hasExportableContent()) {
                    profiles.add(profile);
                    log.info("Collected profile u/{} with {} social links", profile.userId(),
                            profile.socialLinks().size());
                } else {
                    log.info("Skipped profile u/{} because description and social links are empty", profile.userId());
                }
                if (!profile.hasExportableContent()) {
                    cache.markVisited(profile.userId());
                }
            } catch (RuntimeException exception) {
                log.warn("Could not parse profile {}: {}", profileUri, exception.getMessage());
            }
        }

        exportAndMarkVisited(profiles);
    }

    private void exportAndMarkVisited(List<ProfileRecord> profiles) throws IOException {
        Path file = csvExportService.export(profiles);
        log.info("Finished: {} new profiles written to {}", profiles.size(), file);
        for (ProfileRecord profile : profiles) {
            cache.markVisited(profile.userId());
        }
    }

    private String safeGet(URI uri) throws InterruptedException, RedditClientErrorException {
        try {
            return httpClient.get(uri);
        } catch (RedditClientErrorException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            log.warn("Skipping {}: {}", uri, exception.getMessage());
            return null;
        }
    }

    private URI withChallengeQuery(URI uri, Challenge challenge) {
        StringBuilder query = new StringBuilder("solution=").append(encode(challenge.solution()));
        challenge.fields().forEach((key, value) -> query.append('&').append(encode(key)).append('=').append(encode(value)));
        return URI.create(uri + "?" + query);
    }

    private URI addExternalReferrer(URI uri) {
        String separator = uri.getQuery() == null ? "?" : "&";
        return URI.create(uri + separator + "ext-referrer=EXTERNAL");
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
