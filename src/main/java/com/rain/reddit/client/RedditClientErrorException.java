package com.rain.reddit.client;

import java.io.IOException;

public class RedditClientErrorException extends IOException {
    public RedditClientErrorException(int statusCode, String uri) {
        super("Reddit returned HTTP " + statusCode + " for " + uri + " after 3 retries");
    }
}
