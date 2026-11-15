package com.rain.reddit.domain;

import java.net.URI;
import java.util.List;

public record ListingPage(List<ListingPost> posts, URI nextPageUrl) {
}
