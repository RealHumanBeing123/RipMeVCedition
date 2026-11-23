package com.rarchives.ripme.tst.ripper.rippers;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.rarchives.ripme.ripper.rippers.DeviantartArtRipper;
import com.rarchives.ripme.ripper.rippers.DeviantartGalleryRipper;
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

    @Test
    public void testGalleryGid() throws IOException, URISyntaxException {
        URL all = new URI("https://www.deviantart.com/yolabayola1/gallery").toURL();
        Assertions.assertEquals("yolabayola1_gallery_all", new DeviantartGalleryRipper(all).getGID(all));
        URL folder = new URI("https://www.deviantart.com/yolabayola1/gallery/81494518/featured").toURL();
        Assertions.assertEquals("yolabayola1_gallery_81494518", new DeviantartGalleryRipper(folder).getGID(folder));
        Assertions.assertFalse(new DeviantartGalleryRipper(all).canRip(new URI("https://www.deviantart.com/yolabayola1/art/Pounced-1020701055").toURL()));
        Assertions.assertFalse(new DeviantartArtRipper(new URI("https://www.deviantart.com/yolabayola1/art/Pounced-1020701055").toURL())
                .canRip(all));
        Assertions.assertThrows(Exception.class, () -> new DeviantartGalleryRipper(
                new URI("https://www.deviantart.com/yolabayola1/art/Pounced-1020701055").toURL()));
    }
}
