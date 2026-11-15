package com.rain.reddit.domain;

import java.net.URI;

public record CommentContinuation(URI uri, String cursor) {
}
