package com.rain.reddit.domain;

import java.util.Map;

public record Challenge(String solution, Map<String, String> fields) {
}
