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
                <article id='post-123'>
                  <a aria-label='Author: u/Alice' href='https://www.reddit.com/user/Alice/'>Alice</a>
                  <shreddit-post permalink='/r/test/comments/123/title/' comment-count='12'></shreddit-post>
                </article>
                <faceplate-partial slot='load-after' src='/svc/shreddit/community-more-posts/best/?after=abc&amp;t=DAY'></faceplate-partial>
                """;
        ListingPage page = parser.parseListing(html, URI.create("https://www.reddit.com/r/test/"));
        assertThat(page.profileUrls()).containsExactly(URI.create("https://www.reddit.com/user/Alice/"));
        assertThat(page.commentUrls()).containsExactly(URI.create("https://www.reddit.com/r/test/comments/123/title/"));
        assertThat(page.nextPageUrl().toString()).contains("after=abc").contains("t=DAY");
    }

    @Test
    void onlyCollectsPostsWithIdAndPositiveCommentCount() {
        String html = """
                <article id='post-456' data-post-id='t3_456'>
                  <div><shreddit-post permalink='/r/test/comments/456/another_post/' comment-count='2'></shreddit-post></div>
                </article>
                <article id='post-0'><shreddit-post permalink='/r/test/comments/0/no_comments/' comment-count='0'></shreddit-post></article>
                <article id='post-invalid'><shreddit-post permalink='/r/test/comments/1/invalid/' comment-count='unknown'></shreddit-post></article>
                <article><shreddit-post permalink='/r/test/comments/2/no_id/' comment-count='3'></shreddit-post></article>
                <article id='post-other'><shreddit-post permalink='https://example.com/comments/3/' comment-count='4'></shreddit-post></article>
                """;

        ListingPage page = parser.parseListing(html, URI.create("https://www.reddit.com/r/test/"));

        assertThat(page.commentUrls()).containsExactly(
                URI.create("https://www.reddit.com/r/test/comments/456/another_post/"));
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
