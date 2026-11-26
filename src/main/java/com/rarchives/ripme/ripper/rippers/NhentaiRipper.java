package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.ripper.DownloadThreadPool;
import com.rarchives.ripme.ui.RipStatusMessage;
import com.rarchives.ripme.utils.Http;
import com.rarchives.ripme.utils.RipUtils;
import com.rarchives.ripme.utils.Utils;

public class NhentaiRipper extends AbstractHTMLRipper {

    private static final Logger logger = LogManager.getLogger(NhentaiRipper.class);

    private Document firstPage;

    @Override
    public boolean hasQueueSupport() {
        return true;
    }

    @Override
    public boolean pageContainsAlbums(URL url) {
        Pattern pa = Pattern.compile("^https?://nhentai\\.net/tag/([a-zA-Z0-9_\\-]+)/?");
        Matcher ma = pa.matcher(url.toExternalForm());
        return ma.matches();
    }

    @Override
    public List<String> getAlbumsToQueue(Document doc) {
        List<String> urlsToAddToQueue = new ArrayList<>();
        for (Element elem : doc.select("a.cover")) {
            urlsToAddToQueue.add("https://" + getDomain() + elem.attr("href"));
        }
        return urlsToAddToQueue;
    }

    public NhentaiRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getDomain() {
        return "nhentai.net";
    }

    @Override
    public String getHost() {
        return "nhentai";
    }

    @Override
    public String getAlbumTitle(URL url) throws MalformedURLException {
        try {
            loadPage();
        } catch (IOException e) {
            logger.warn("Could not read the gallery title: {}", e.getMessage());
        }
        String title = firstPage == null ? "" : firstPage.select("#info h1, h1.title").text().trim();
        if (title.isEmpty()) {
            return "nhentai_" + getGID(url);
        }
        return "nhentai_" + title;
    }

    private List<String> apiImages = null; // used when the web page itself cannot be read

    /** True when the page shows the gallery's thumbnails. */
    private static boolean looksLikeGallery(Document doc) {
        return doc != null && !doc.select("a.gallerythumb img").isEmpty();
    }

    /**
     * Loads the gallery page. nhentai's protection answers differently depending on who seems to be asking, so a few
     * different identities are tried (your own cookies first), and as a last resort the gallery's data address.
     */
    private void loadPage() throws IOException {
        if (firstPage != null) {
            return;
        }
        String[] agents = { null, "curl/8.4.0", "RipMeVCedition/1.0 (+https://github.com/RealHumanBeing123/RipMeVCedition)" };
        StringBuilder tried = new StringBuilder();
        for (String agent : agents) {
            String label = agent == null ? "default" : agent.split("[ /]")[0];
            try {
                Http http = Http.url(url).retries(1);
                if (agent != null) {
                    http.userAgent(agent);
                }
                http.connection().ignoreHttpErrors(true);
                org.jsoup.Connection.Response r = http.response();
                Document doc = Jsoup.parse(r.body(), url.toExternalForm());
                if (looksLikeGallery(doc)) {
                    firstPage = doc;
                    return;
                }
                tried.append("\n  page [").append(label).append("] -> HTTP ").append(r.statusCode()).append(" title '")
                        .append(doc.title()).append("'");
            } catch (IOException e) {
                tried.append("\n  page [").append(label).append("] -> ").append(e.getMessage());
            }
        }
        // last resort: the data address behind the page
        String id = getGID(url);
        for (String agent : agents) {
            String label = agent == null ? "default" : agent.split("[ /]")[0];
            try {
                Http http = Http.url("https://nhentai.net/api/gallery/" + id).ignoreContentType().retries(1);
                if (agent != null) {
                    http.userAgent(agent);
                }
                http.connection().ignoreHttpErrors(true);
                org.jsoup.Connection.Response r = http.response();
                String body = r.body();
                try {
                    List<String> found = imagesFromApi(new JSONObject(body));
                    if (!found.isEmpty()) {
                        apiImages = found;
                        firstPage = Document.createShell(url.toExternalForm());
                        return;
                    }
                    tried.append("\n  api [").append(label).append("] -> no pages in the answer");
                } catch (org.json.JSONException e) {
                    tried.append("\n  api [").append(label).append("] -> HTTP ").append(r.statusCode()).append(' ')
                            .append(body.length() > 80 ? body.substring(0, 80) : body).append(' ');
                }
            } catch (IOException e) {
                tried.append("\n  api [").append(label).append("] -> ").append(e.getMessage());
            }
        }
        throw new IOException("nhentai did not show the gallery to the ripper (it may be asking for a browser check). "
                + "Try Configuration -> Site cookies -> Import from browser for nhentai.net after opening the gallery "
                + "in your browser. Every attempt:" + tried);
    }

    /** Builds the full-size picture addresses from the gallery's data: {"media_id":"..","images":{"pages":[{"t":"j"}]}}. */
    public static List<String> imagesFromApi(JSONObject gallery) {
        List<String> urls = new ArrayList<>();
        String media = gallery.optString("media_id", "");
        JSONObject images = gallery.optJSONObject("images");
        JSONArray pages = images == null ? null : images.optJSONArray("pages");
        if (media.isEmpty() || pages == null) {
            return urls;
        }
        for (int i = 0; i < pages.length(); i++) {
            String t = pages.getJSONObject(i).optString("t", "j");
            String ext = t.equals("p") ? "png" : t.equals("g") ? "gif" : t.equals("w") ? "webp" : "jpg";
            urls.add("https://i" + (i % 4 + 1) + ".nhentai.net/galleries/" + media + "/" + (i + 1) + "." + ext);
        }
        return urls;
    }

    public List<String> getTags(Document doc) {
        List<String> tags = new ArrayList<>();
        for (Element tag : doc.select("a.tag")) {
            String tagString = tag.attr("href").replaceAll("/tag/", "").replaceAll("/", "");
            logger.info("Found tag: " + tagString);
            tags.add(tagString);
        }
        return tags;
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        // Ex: https://nhentai.net/g/159174/
        Pattern p = Pattern.compile("^https?://nhentai\\.net/g/(\\d+).*$");
        Matcher m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            // Return the text contained between () in the regex - 159174 in this case
            return m.group(1);
        }
        throw new MalformedURLException("Expected nhentai.net URL format: " +
                "nhentai.net/g/albumid - got " + url + " instead");
    }

    @Override
    public Document getFirstPage() throws IOException {
        loadPage();

        String blacklistedTag = RipUtils.checkTags(Utils.getConfigStringArray("nhentai.blacklist.tags"), getTags(firstPage));
        if (blacklistedTag != null) {
            sendUpdate(RipStatusMessage.STATUS.DOWNLOAD_WARN, "Skipping " + url.toExternalForm() + " as it " +
                    "contains the blacklisted tag \"" + blacklistedTag + "\"");
            return null;
        }
        return firstPage;
    }

    @Override
    public List<String> getURLsFromPage(Document page) {
        if (apiImages != null) {
            return apiImages;
        }
        // Set: lazy-loaded thumbnails are sometimes repeated inside <noscript> blocks
        Set<String> imageURLs = new LinkedHashSet<>();
        Elements thumbs = page.select("a.gallerythumb img");
        for (Element el : thumbs) {
            String thumb = firstUsableImageUrl(el);
            if (thumb == null) {
                logger.warn("Could not find an image address in: {}", el.outerHtml());
                continue;
            }
            imageURLs.add(fullSizeFromThumbnail(thumb));
        }
        return new ArrayList<>(imageURLs);
    }

    /**
     * "https://t2.nhentai.net/galleries/123/5t.webp.webp" (a thumbnail that was converted to webp) -> the
     * full page image "https://i2.nhentai.net/galleries/123/5.webp"; "5t.jpg" -> "5.jpg".
     */
    public static String fullSizeFromThumbnail(String thumb) {
        String full = thumb.replaceFirst("://t(\\d*)\\.", "://i$1.");
        full = full.replaceAll("(/\\d+)t\\.", "$1.");
        // the thumbnail's own ".webp" suffix is not part of the full image's name
        return full.replaceAll("\\.(jpg|jpeg|png|gif|webp)\\.webp(\\?.*)?$", ".$1$2");
    }

    /**
     * The thumbnail address may be in data-src (lazy loading) or src, and may be relative or
     * protocol-relative ("//t3.nhentai.net/..."). Returns an absolute address or null.
     */
    private static String firstUsableImageUrl(Element img) {
        for (String attr : new String[] { "data-src", "data-original", "data-lazy-src", "src" }) {
            String value = img.absUrl(attr);
            if (value.isEmpty()) {
                String raw = img.attr(attr).trim();
                if (raw.startsWith("//")) {
                    value = "https:" + raw;
                }
            }
            if (!value.isEmpty() && !value.startsWith("data:")) {
                return value;
            }
        }
        return null;
    }

    @Override
    public void downloadURL(URL url, int index) {
        addURLToDownload(url, getPrefix(index), "", this.url.toExternalForm(), null);
    }


}
