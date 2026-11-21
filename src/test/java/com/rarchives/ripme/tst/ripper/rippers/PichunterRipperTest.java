package com.rarchives.ripme.tst.ripper.rippers;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import com.rarchives.ripme.ripper.rippers.PichunterRipper;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

public class PichunterRipperTest extends RippersTest {
    @Test
    @Tag("flaky")
    public void testPichunterModelPageRip() throws IOException, URISyntaxException {
        // A non-photoset
        PichunterRipper ripper = new PichunterRipper(new URI("https://www.pichunter.com/models/Abby_C").toURL());
        testRipper(ripper);
    }

    @Test
    @Tag("flaky")
    public void testPichunterGalleryRip() throws IOException, URISyntaxException {
        // a photo set
        PichunterRipper ripper = new PichunterRipper(
                new URI("https://www.pichunter.com/gallery/802829/Sapphic_sluts_having_fun_for_D").toURL());
        testRipper(ripper);
    }

    @Test
    public void testGetGID() throws IOException, URISyntaxException {
        PichunterRipper creator = new PichunterRipper(new URI("https://www.pichunter.com/creators/miamalkova").toURL());
        org.junit.jupiter.api.Assertions.assertEquals("miamalkova", creator.getGID(creator.getURL()));
        PichunterRipper gallery = new PichunterRipper(new URI("https://www.pichunter.com/gallery/802829/Sapphic_sluts_having_fun_for_D").toURL());
        org.junit.jupiter.api.Assertions.assertEquals("Sapphic_sluts_having_fun_for_D", gallery.getGID(gallery.getURL()));
    }
}
