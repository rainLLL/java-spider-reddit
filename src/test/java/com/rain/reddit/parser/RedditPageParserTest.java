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
    void parsesDocumentedCommentContinuationChain() {
        URI commentsUri = URI.create("https://www.reddit.com/r/test/comments/123/title/");
        String initialHtml = """
                <faceplate-partial id='top-level-more-comments-partial' method='post'
                    src='/svc/shreddit/more-comments/test/t3_123?top-level=1&amp;comments-remaining=75'>
                  <input type='hidden' name='cursor' value='first+cursor='>
                </faceplate-partial>
                """;
        var first = parser.parseTopLevelComments(initialHtml, commentsUri).orElseThrow();
        assertThat(first.uri()).isEqualTo(URI.create(
                "https://www.reddit.com/svc/shreddit/more-comments/test/t3_123?top-level=1&comments-remaining=75"));
        assertThat(first.cursor()).isEqualTo("first+cursor=");

        String moreHtml = """
                <faceplate-partial class='mx-md xs:mx-0 more-comments-partial' method='post'
                    src='/svc/shreddit/more-comments/test/t3_123?top-level=1&amp;rootCommentOffset=8'>
                  <input type='hidden' name='cursor' value='next/cursor='>
                </faceplate-partial>
                """;
        var next = parser.parseNextComments(moreHtml, first.uri()).orElseThrow();
        assertThat(next.uri().toString()).contains("rootCommentOffset=8");
        assertThat(next.cursor()).isEqualTo("next/cursor=");
    }

    @Test
    void ignoresIncompleteOrExternalCommentContinuations() {
        URI uri = URI.create("https://www.reddit.com/r/test/comments/123/title/");
        assertThat(parser.parseTopLevelComments("<p>No more comments</p>", uri)).isEmpty();
        assertThat(parser.parseTopLevelComments("""
                <faceplate-partial id='top-level-more-comments-partial' method='post'
                    src='/svc/shreddit/more-comments/test/t3_123'></faceplate-partial>
                """, uri)).isEmpty();
        assertThat(parser.parseNextComments("""
                <faceplate-partial class='more-comments-partial' method='post'
                    src='https://example.com/svc/shreddit/more-comments/test/t3_123'>
                  <input name='cursor' value='abc'>
                </faceplate-partial>
                """, uri)).isEmpty();
    }

    @Test
    void parsesProfileDescriptionAndMultipleSocialLinks() {
        String html = """
                <p data-testid='profile-description'>Engineer &amp; trader. Find me on Instagram.</p>
                <faceplate-tracker noun='social_link'><a href='https://www.instagram.com/alice/'>Instagram</a></faceplate-tracker>
                <faceplate-tracker noun='social_link'><a href='https://discord.gg/example'>Discord</a></faceplate-tracker>
                """;
        ProfileRecord profile = parser.parseProfile(html, URI.create("https://www.reddit.com/user/Alice/"));
        assertThat(profile.userId()).isEqualTo("Alice");
        assertThat(profile.description()).isEqualTo("Engineer & trader. Find me on Instagram.");
        assertThat(profile.socialLinks()).containsExactly("https://www.instagram.com/alice/", "https://discord.gg/example");
    }

    @Test
    void keepsWholeDescriptionForEachSocialKeywordIgnoringCase() {
        for (String keyword : new String[] {"IG", "insta", "Instagram", "fb", "Facebook", "discord"}) {
            String description = "Hello, find me on " + keyword.toUpperCase() + "! More details here.";
            ProfileRecord profile = parser.parseProfile(
                    "<p data-testid='profile-description'>" + description + "</p>",
                    URI.create("https://www.reddit.com/user/Alice/"));
            assertThat(profile.description()).isEqualTo(description);
        }
    }

    @Test
    void clearsUnmatchedDescriptionButPreservesSocialLinks() {
        String html = """
                <p data-testid='profile-description'>A big fabric enthusiast</p>
                <faceplate-tracker noun='social_link'><a href='https://x.com/alice'>X</a></faceplate-tracker>
                """;
        ProfileRecord profile = parser.parseProfile(html, URI.create("https://www.reddit.com/user/Alice/"));

        assertThat(profile.description()).isEmpty();
        assertThat(profile.socialLinks()).containsExactly("https://x.com/alice");
        assertThat(profile.hasExportableContent()).isTrue();
    }

    @Test
    void keepsOnlySupportedPlatformDomains() {
        String html = """
                <faceplate-tracker noun='social_link'><a href='https://www.facebook.com/alice'>Facebook</a></faceplate-tracker>
                <faceplate-tracker noun='social_link'><a href='https://instagram.com/alice'>Instagram</a></faceplate-tracker>
                <faceplate-tracker noun='social_link'><a href='https://discord.com/invite/example'>Discord</a></faceplate-tracker>
                <faceplate-tracker noun='social_link'><a href='https://x.com/alice'>X</a></faceplate-tracker>
                <faceplate-tracker noun='social_link'><a href='https://twitter.com/alice'>Twitter legacy</a></faceplate-tracker>
                <faceplate-tracker noun='social_link'><a href='https://example.com/profile'>Other</a></faceplate-tracker>
                <faceplate-tracker noun='social_link'><a href='https://facebook.com.evil.test/alice'>Lookalike</a></faceplate-tracker>
                <faceplate-tracker noun='social_link'><a href='https://evil.test/facebook.com'>Path only</a></faceplate-tracker>
                <faceplate-tracker noun='social_link'><a href='javascript:alert(1)'>Script</a></faceplate-tracker>
                """;

        ProfileRecord profile = parser.parseProfile(html, URI.create("https://www.reddit.com/user/Alice/"));

        assertThat(profile.socialLinks()).containsExactly(
                "https://www.facebook.com/alice", "https://instagram.com/alice",
                "https://discord.com/invite/example", "https://x.com/alice", "https://twitter.com/alice");
    }
}
