package com.rarchives.ripme.tst.ripper.rippers;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.rarchives.ripme.ripper.rippers.DeviantartArtRipper;
import com.rarchives.ripme.ripper.rippers.FivehundredpxPhotoRipper;

public class DeviantartArtRipperTest {
    @Test
    public void testArtGid() throws IOException, URISyntaxException {
        URL u = new URI("https://www.deviantart.com/yolabayola1/art/Pounced-1020701055").toURL();
        Assertions.assertEquals("yolabayola1_art_1020701055", new DeviantartArtRipper(u).getGID(u));
        URL u2 = new URI("https://www.deviantart.com/lizeart/art/1-871973040").toURL();
        Assertions.assertEquals("lizeart_art_871973040", new DeviantartArtRipper(u2).getGID(u2));
    }

    @Test
    public void testFivehundredpxPhotoGid() throws IOException, URISyntaxException {
        URL u = new URI("https://500px.com/photo/azrKSPoE4vH").toURL();
        Assertions.assertEquals("photo_azrKSPoE4vH", new FivehundredpxPhotoRipper(u).getGID(u));
    }
}
