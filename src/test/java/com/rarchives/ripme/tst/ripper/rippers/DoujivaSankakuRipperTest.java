package com.rarchives.ripme.tst.ripper.rippers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.rarchives.ripme.ripper.rippers.DoujivaRipper;
import com.rarchives.ripme.ripper.rippers.SankakuComplexRipper;
import com.rarchives.ripme.ripper.rippers.SankakuForumRipper;

public class DoujivaSankakuRipperTest {
    @Test
    public void testDoujivaGid() throws Exception {
        DoujivaRipper series = new DoujivaRipper(new URI("https://doujiva.com/manga/charisma-check").toURL());
        assertEquals("charisma-check", series.getGID(series.getURL()));
        DoujivaRipper chapter = new DoujivaRipper(new URI("https://doujiva.com/manga/switch-on-season-2/read/cmpaeka2500170tp78i3he0hw?page=2").toURL());
        assertEquals("switch-on-season-2_cmpaeka2500170tp78i3he0hw", chapter.getGID(chapter.getURL()));
        assertFalse(series.canRip(new URI("https://doujiva.com/").toURL()));
    }

    @Test
    public void testDoujivaChapterList() {
        String page = "x \"chapters\":[{\"id\":\"a1\",\"number\":0,\"title\":\"Read First\",\"pageCount\":6},"
                + "{\"id\":\"b2\",\"number\":1,\"title\":\"One\",\"pageCount\":30}] y";
        assertEquals(2, DoujivaRipper.parseChapters(page).size());
        assertEquals(1, DoujivaRipper.imagesInText("https://cdn.doujiva.com/a/chapter-1/001.webp https://cdn.doujiva.com/a/chapter-1/001.thumb.webp https://cdn.doujiva.com/a/chapter-2/001.webp", "a", 1).size());
    }

    @Test
    public void testSankakuForumThread() throws Exception {
        SankakuForumRipper r = new SankakuForumRipper(new URI("https://forum.sankakucomplex.com/t/ai-companions-feedback/41372").toURL());
        assertEquals("thread_41372", r.getGID(r.getURL()));
        assertTrue(r.canRip(new URI("https://forum.sankakucomplex.com/t/41372/5").toURL()));
        assertFalse(r.canRip(new URI("https://forum.sankakucomplex.com/tag/art/14").toURL()));
    }

    @Test
    public void testSankakuSinglePost() throws Exception {
        SankakuComplexRipper r = new SankakuComplexRipper(new URI("https://www.sankakucomplex.com/posts/k3R9xA528rG?tab=explore").toURL());
        assertEquals("post_k3R9xA528rG", r.getGID(r.getURL()));
        assertFalse(r.canRip(new URI("https://forum.sankakucomplex.com/t/a/1").toURL()));
        assertFalse(r.canRip(new URI("https://news.sankakucomplex.com/n/abc").toURL()));
    }
}
