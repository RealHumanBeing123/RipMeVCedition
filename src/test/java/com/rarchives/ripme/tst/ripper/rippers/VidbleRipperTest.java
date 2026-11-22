package com.rarchives.ripme.tst.ripper.rippers;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import com.rarchives.ripme.ripper.rippers.VidbleRipper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

public class VidbleRipperTest extends RippersTest {
    @Test
    @Tag("flaky")
    public void testVidbleRip() throws IOException, URISyntaxException {
        VidbleRipper ripper = new VidbleRipper(new URI("https://vidble.com/album/cGEFr8zi").toURL());
        testRipper(ripper);
    }

    @Test
    public void testGetGID() throws IOException, URISyntaxException {
        URL url = new URI("https://vidble.com/album/cGEFr8zi").toURL();
        VidbleRipper ripper = new VidbleRipper(url);
        Assertions.assertEquals("cGEFr8zi", ripper.getGID(url));
    }

    @org.junit.jupiter.api.Test
    public void testGetGIDForVideoAndDirectLinks() throws java.io.IOException, java.net.URISyntaxException {
        com.rarchives.ripme.ripper.rippers.VidbleRipper video = new com.rarchives.ripme.ripper.rippers.VidbleRipper(
                new java.net.URI("https://vidble.com/watch?v=6FImmkCzg3ukzr2DsrrJJ1zt0ZdFc34q").toURL());
        org.junit.jupiter.api.Assertions.assertEquals("watch_6FImmkCzg3ukzr2DsrrJJ1zt0ZdFc34q", video.getGID(video.getURL()));
        com.rarchives.ripme.ripper.rippers.VidbleRipper image = new com.rarchives.ripme.ripper.rippers.VidbleRipper(
                new java.net.URI("https://vidble.com/explore/ERmtOLXaiZ.jpg").toURL());
        org.junit.jupiter.api.Assertions.assertEquals("ERmtOLXaiZ", image.getGID(image.getURL()));
    }
}
