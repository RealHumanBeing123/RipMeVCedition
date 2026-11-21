package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.utils.Http;

public class PichunterRipper extends AbstractHTMLRipper {

    private static final Logger logger = LogManager.getLogger(PichunterRipper.class);

    public PichunterRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "pichunter";
    }

    @Override
    public String getDomain() {
        return "pichunter.com";
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Pattern p = Pattern.compile("https?://www.pichunter.com/(|tags|models|sites|creators)/(\\S*)/?");
        Matcher m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            return m.group(2);
        }
        p = Pattern.compile("https?://www.pichunter.com/(tags|models|sites|creators)/(\\S*)/photos/\\d+/?");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            return m.group(2);
        }
        p = Pattern.compile("https?://www.pichunter.com/tags/all/(\\S*)/\\d+/?");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            return m.group(1);
        }

        p = Pattern.compile("https?://www.pichunter.com/gallery/\\d+/(\\S*)/?");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            return m.group(1);
        }
        throw new MalformedURLException("Expected pichunter URL format: " +
                "pichunter.com/(tags|models|sites|creators)/Name/ - got " + url + " instead");
    }

    private boolean isPhotoSet(URL url) {
        Pattern p = Pattern.compile("https?://www.pichunter.com/gallery/\\d+/(\\S*)/?");
        Matcher m = p.matcher(url.toExternalForm());
        return m.matches();
    }

    @Override
    public Document getNextPage(Document doc) throws IOException {
        Element link = doc.select("a[rel=next]").first();
        if (link == null) {
            link = doc.select("ul.pagination li.active + li a, li.page-item.active + li.page-item a").first();
        }
        if (link == null) {
            Element elem = doc.select("div.paperSpacings > ul > li.arrow").last();
            if (elem != null) {
                link = elem.select("a").first();
            }
        }
        if (link != null) {
            String nextPage = link.absUrl("href");
            // Sometimes the link is empty or points back to the same page
            if (!nextPage.isEmpty() && !nextPage.equals(doc.location())) {
                return Http.url(nextPage).get();
            }
        }
        throw new IOException("No more pages");
    }

    /** Full-size pictures of one gallery page: the link around every thumbnail points at the big picture. */
    private static List<String> picturesOfGallery(Document doc) {
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        for (Element a : doc.select("a[data-fancybox][href]")) {
            String href = a.absUrl("href");
            if (href.matches("(?i)^[^?#]*\\.(jpe?g|png|gif|webp)(\\?.*)?$")) {
                found.add(href);
            }
        }
        if (found.isEmpty()) {
            // no links: use the thumbnails and ask for the big version of each
            for (Element img : doc.select("article.grid-item img, .gallery-grid img")) {
                String src = img.absUrl("src");
                if (src.contains("post_archive_thumb")) {
                    found.add(src.replace("post_archive_thumb", "post_single_big"));
                }
            }
        }
        return new ArrayList<>(found);
    }

    /** Which gallery (folder name) a picture belongs to, for pages that list several galleries. */
    private final java.util.Map<String, String> folders = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public List<String> getURLsFromPage(Document doc) {
        if (isPhotoSet(url)) {
            List<String> pictures = picturesOfGallery(doc);
            if (pictures.isEmpty()) {
                logger.warn("Pichunter: no pictures recognised on " + doc.location() + " (page has "
                        + doc.select("img").size() + " img tags)");
            }
            return pictures;
        }
        // A model, creator, tag or site page lists galleries: open each one and take all of its pictures
        List<String> result = new ArrayList<>();
        java.util.LinkedHashSet<String> galleries = new java.util.LinkedHashSet<>();
        for (Element a : doc.select("article.grid-item a.item-link[href*=/gallery/], a.item-link[href*=/gallery/]")) {
            galleries.add(a.absUrl("href"));
        }
        for (String gallery : galleries) {
            if (isStopped() || (isThisATest() && !result.isEmpty())) {
                break;
            }
            try {
                Matcher m = Pattern.compile("/gallery/(\\d+)/([^/?#]*)").matcher(gallery);
                String folder = m.find() ? m.group(1) + "_" + m.group(2) : "gallery";
                sleep(300);
                for (String picture : picturesOfGallery(Http.url(gallery).get())) {
                    folders.put(picture, folder);
                    result.add(picture);
                }
            } catch (IOException e) {
                logger.warn("Could not load gallery " + gallery, e);
            }
        }
        if (result.isEmpty()) {
            logger.warn("Pichunter: no galleries or pictures recognised on " + doc.location());
        }
        return result;
    }

    @Override
    public void downloadURL(URL url, int index) {
        java.util.Map<String, String> options = new java.util.HashMap<>();
        options.put("prefix", getPrefix(index));
        options.put("referrer", "https://www.pichunter.com/");
        String folder = folders.get(url.toExternalForm());
        if (folder != null) {
            options.put("subdirectory", folder);
        }
        addURLToDownload(url, options);
    }
}
