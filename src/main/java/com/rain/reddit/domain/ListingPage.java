package com.rain.reddit.domain;

import java.net.URI;
import java.util.Set;

public record ListingPage(Set<URI> profileUrls, Set<URI> commentUrls, URI nextPageUrl) {
}
