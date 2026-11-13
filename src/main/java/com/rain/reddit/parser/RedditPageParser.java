package com.rain.reddit.parser;

import com.rain.reddit.domain.Challenge;
import com.rain.reddit.domain.ListingPage;
import com.rain.reddit.domain.ProfileRecord;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class RedditPageParser {

    private static final URI BASE_URI = URI.create("https://www.reddit.com");
    private static final Pattern CHALLENGE_VALUE = Pattern.compile("(?:async\\s*)?e\\s*=>\\s*e\\s*\\+\\s*e\\s*\\)\\s*\\(\\s*[\\\"']([^\\\"']+)[\\\"']");
    private static final Pattern USER_PATH = Pattern.compile("^/user/([^/]+)/?.*$", Pattern.CASE_INSENSITIVE);

    public Optional<Challenge> parseChallenge(String html) {
        Document document = Jsoup.parse(html);
        Element flag = document.selectFirst("input[name=js_challenge]");
        if (flag == null) {
            return Optional.empty();
        }

        String scripts = document.select("script").stream()
                .map(Element::data)
                .collect(Collectors.joining("\n"));
        Matcher matcher = CHALLENGE_VALUE.matcher(scripts);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Reddit JavaScript challenge found, but its value formula is unsupported");
        }

        Map<String, String> fields = new LinkedHashMap<>();
        document.select("input[name=js_challenge], input[name=jsc_token], input[name=jsc_orig_r]")
                .forEach(input -> fields.put(input.attr("name"), input.attr("value")));
        String seed = matcher.group(1);
        return Optional.of(new Challenge(seed + seed, fields));
    }

    public ListingPage parseListing(String html, URI pageUri) {
        Document document = Jsoup.parse(html, pageUri.toString());
        Set<URI> profiles = extractProfileUrls(document);
        Set<URI> comments = new LinkedHashSet<>();
        document.select("article a[data-action-bar-action=comments][href]")
                .forEach(link -> toRedditUri(link.absUrl("href")).ifPresent(comments::add));

        URI next = null;
        Element nextElement = document.selectFirst("faceplate-partial[slot=load-after][src]");
        if (nextElement != null) {
            next = toRedditUri(nextElement.absUrl("src")).orElse(null);
        }
        return new ListingPage(profiles, comments, next);
    }

    public Set<URI> parseCommentProfiles(String html, URI pageUri) {
        return extractProfileUrls(Jsoup.parse(html, pageUri.toString()));
    }

    public ProfileRecord parseProfile(String html, URI profileUri) {
        Document document = Jsoup.parse(html, profileUri.toString());
        String userId = userIdFromUri(profileUri)
                .orElseThrow(() -> new IllegalArgumentException("Invalid Reddit profile URL: " + profileUri));
        Element descriptionElement = document.selectFirst("[data-testid=profile-description]");
        String description = descriptionElement == null ? "" : descriptionElement.text().trim();

        List<String> links = new ArrayList<>();
        document.select("faceplate-tracker[noun=social_link] a[href]").forEach(anchor -> {
            String link = anchor.absUrl("href");
            if (!link.isBlank() && !links.contains(link)) {
                links.add(link);
            }
        });
        return new ProfileRecord(userId, description, List.copyOf(links));
    }

    public Optional<String> userIdFromUri(URI uri) {
        Matcher matcher = USER_PATH.matcher(uri.getPath());
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }

    private Set<URI> extractProfileUrls(Document document) {
        Set<URI> profiles = new LinkedHashSet<>();
        document.select("a[href*=/user/]").forEach(anchor -> {
            String ariaLabel = anchor.attr("aria-label");
            boolean authorLink = ariaLabel.startsWith("Author: u/")
                    || anchor.hasClass("text-neutral-content-strong")
                    || anchor.attr("href").contains("/user/");
            if (authorLink) {
                toRedditUri(anchor.absUrl("href"))
                        .filter(uri -> userIdFromUri(uri).isPresent())
                        .ifPresent(profiles::add);
            }
        });
        return profiles;
    }

    private Optional<URI> toRedditUri(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        URI uri = BASE_URI.resolve(value.replace("&amp;", "&"));
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || !(host.equalsIgnoreCase("www.reddit.com") || host.equalsIgnoreCase("reddit.com"))) {
            return Optional.empty();
        }
        return Optional.of(uri);
    }
}
