package com.rain.reddit.domain;

import java.util.List;

public record ProfileRecord(String userId, String description, List<String> socialLinks) {

    public boolean hasExportableContent() {
        return !description.isBlank() || !socialLinks.isEmpty();
    }
}
