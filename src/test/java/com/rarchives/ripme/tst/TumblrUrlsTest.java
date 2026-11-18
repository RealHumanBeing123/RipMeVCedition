package com.rarchives.ripme.tst;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.rarchives.ripme.utils.TumblrUrls;

public class TumblrUrlsTest {

    @Test
    void postUrlWithSlug() {
        assertEquals("https://silis-art.tumblr.com/post/831089128240185344", TumblrUrls
                .fromNewStyle("https://www.tumblr.com/silis-art/831089128240185344/hello-its-been-a-while"));
    }

    @Test
    void blogUrl() {
        assertEquals("https://beautifulbabes777.tumblr.com/", TumblrUrls.fromNewStyle("https://www.tumblr.com/beautifulbabes777"));
        assertEquals("https://silis-art.tumblr.com/", TumblrUrls.fromNewStyle("https://www.tumblr.com/silis-art/"));
    }

    @Test
    void taggedUrl() {
        assertEquals("https://silis-art.tumblr.com/tagged/illustration",
                TumblrUrls.fromNewStyle("https://www.tumblr.com/silis-art/tagged/illustration"));
    }

    @Test
    void queryStringIsDropped() {
        assertEquals("https://b.tumblr.com/post/123", TumblrUrls.fromNewStyle("https://www.tumblr.com/b/123/slug?source=share"));
    }

    @Test
    void blogViewUrl() {
        assertEquals("https://silis-art.tumblr.com/post/831089128240185344",
                TumblrUrls.fromNewStyle("https://www.tumblr.com/blog/view/silis-art/831089128240185344"));
    }

    @Test
    void otherUrlsAreLeftAlone() {
        assertNull(TumblrUrls.fromNewStyle("https://silis-art.tumblr.com/post/831089128240185344/x"));
        assertNull(TumblrUrls.fromNewStyle("https://www.tumblr.com/liked/by/someone"));
        assertNull(TumblrUrls.fromNewStyle("https://www.tumblr.com/dashboard"));
        assertNull(TumblrUrls.fromNewStyle("https://www.tumblr.com/tagged/something"));
    }
}
