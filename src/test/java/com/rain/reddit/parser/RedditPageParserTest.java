package com.rain.reddit.parser;

import com.rain.reddit.domain.Challenge;
import com.rain.reddit.domain.ListingPage;
import com.rain.reddit.domain.ProfileRecord;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class RedditPageParserTest {

    private final RedditPageParser parser = new RedditPageParser();

    @Test
    void parsesDocumentedChallenge() {
        String html = """
                <input type='hidden' name='js_challenge' value='1'>
                <input type='hidden' name='jsc_token' value='abc'>
                <input type='hidden' name='jsc_orig_r' value=''>
                <script>var n=(await(async e=>e+e)(\"seed123\"));</script>
                """;
        Challenge challenge = parser.parseChallenge(html).orElseThrow();
        assertThat(challenge.solution()).isEqualTo("seed123seed123");
        assertThat(challenge.fields()).containsEntry("jsc_token", "abc");
    }

    @Test
    void parsesListingProfilesCommentsAndNextPage() {
        String html = """
                <article>
                  <a aria-label='Author: u/Alice' href='https://www.reddit.com/user/Alice/'>Alice</a>
                  <a data-action-bar-action='comments' href='/r/test/comments/123/title/'>Comments</a>
                </article>
                <faceplate-partial slot='load-after' src='/svc/shreddit/community-more-posts/best/?after=abc&amp;t=DAY'></faceplate-partial>
                """;
        ListingPage page = parser.parseListing(html, URI.create("https://www.reddit.com/r/test/"));
        assertThat(page.profileUrls()).containsExactly(URI.create("https://www.reddit.com/user/Alice/"));
        assertThat(page.commentUrls()).containsExactly(URI.create("https://www.reddit.com/r/test/comments/123/title/"));
        assertThat(page.nextPageUrl().toString()).contains("after=abc").contains("t=DAY");
    }

    @Test
    void parsesProfileDescriptionAndMultipleSocialLinks() {
        String html = """
                <p data-testid='profile-description'>Engineer &amp; trader</p>
                <faceplate-tracker noun='social_link'><a href='https://example.com/one'>One</a></faceplate-tracker>
                <faceplate-tracker noun='social_link'><a href='https://example.org/two'>Two</a></faceplate-tracker>
                """;
        ProfileRecord profile = parser.parseProfile(html, URI.create("https://www.reddit.com/user/Alice/"));
        assertThat(profile.userId()).isEqualTo("Alice");
        assertThat(profile.description()).isEqualTo("Engineer & trader");
        assertThat(profile.socialLinks()).containsExactly("https://example.com/one", "https://example.org/two");
    }
}
