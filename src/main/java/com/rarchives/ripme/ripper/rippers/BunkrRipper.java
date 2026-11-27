package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;
import org.jsoup.Connection;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.utils.Http;

/**
 * Bunkr (bunkr.cr, bunkr.ph, bunkr.si ... the site changes its address often, so any bunkr / bunkrr address
 * is accepted). An album link (bunkr.xx/a/ID) rips every file on every page of the album; a file link
 * (bunkr.xx/f/NAME) rips that one file.
 *
 * Getting from a file to the real download takes four steps (the same ones the site's own page does):
 *   1. album page   -> links like /f/k9jVpVQuRUEMv
 *   2. file page    -> a "Download" link like https://dl.bunkr.cr/file/61161542 (the number is the file id)
 *   3. POST {id} to https://dl.bunkr.cr/api/_001_v2 -> {mediafiles, path, original}
 *   4. ask the signing service for a short-lived token for that path, and add it to the address.
 * Because the token runs out, steps 2-4 are done right when each file is about to be downloaded.
 */
public class BunkrRipper extends AbstractHTMLRipper {

    private static final Logger logger = LogManager.getLogger(BunkrRipper.class);

    private static final Pattern LINK = Pattern.compile(
            "^https?://(?:[a-z0-9-]+\\.)?bunkrr?\\.[a-z]{2,8}/(a|f|v|i|d)/([A-Za-z0-9_-]+)/?(?:[?#].*)?$");
    private static final String SIGN_SERVICE = "https://glb-apisign.cdn.cr/sign";

    public BunkrRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "bunkr";
    }

    @Override
    protected String getDomain() {
        return "bunkr.cr";
    }

    @Override
    public boolean canRip(URL url) {
        return LINK.matcher(url.toExternalForm()).matches();
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Matcher m = LINK.matcher(url.toExternalForm());
        if (!m.matches()) {
            throw new MalformedURLException("Expected bunkr.xx/a/ID (album) or bunkr.xx/f/NAME (file) - got " + url);
        }
        return m.group(1).equals("a") ? m.group(2) : "file_" + m.group(2);
    }

    private boolean isAlbum() {
        Matcher m = LINK.matcher(url.toExternalForm());
        return m.matches() && m.group(1).equals("a");
    }

    @Override
    public Document getFirstPage() throws IOException {
        return Http.url(url).get();
    }

    private final Set<String> visitedPages = new HashSet<>();

    @Override
    public Document getNextPage(Document doc) throws IOException {
        if (!isAlbum()) {
            throw new IOException("No more pages");
        }
        visitedPages.add(doc.location());
        Matcher cur = Pattern.compile("[?&]page=(\\d+)").matcher(doc.location());
        int current = cur.find() ? Integer.parseInt(cur.group(1)) : 1;
        for (Element a : doc.select(".pagination a[href]")) {
            Matcher pg = Pattern.compile("[?&]page=(\\d+)").matcher(a.absUrl("href"));
            if (pg.find() && Integer.parseInt(pg.group(1)) == current + 1) {
                String next = a.absUrl("href");
                if (!visitedPages.contains(next)) {
                    sleep(700);
                    return Http.url(next).referrer(doc.location()).get();
                }
            }
        }
        throw new IOException("No more pages");
    }

    @Override
    public String getAlbumTitle(URL url) throws MalformedURLException {
        try {
            Document d = getCachedFirstPage();
            Element t = d.selectFirst("meta[property=og:title]");
            String title = t == null ? "" : t.attr("content").trim();
            if (!title.isEmpty() && isAlbum()) {
                return getHost() + "_" + getGID(url) + "_" + title.replaceAll("[\\\\/:*?\"<>|]", "_");
            }
        } catch (Exception e) {
            // fall back to the plain name
        }
        return super.getAlbumTitle(url);
    }

    @Override
    public List<String> getURLsFromPage(Document page) {
        Set<String> found = new LinkedHashSet<>();
        Matcher self = LINK.matcher(url.toExternalForm());
        if (self.matches() && !self.group(1).equals("a")) {
            found.add(url.toExternalForm());
        } else {
            for (Element a : page.select(".theItem a[href^=/f/], .theItem a[href*=/f/]")) {
                String href = a.absUrl("href");
                if (!href.isEmpty()) {
                    found.add(href);
                }
            }
        }
        return new ArrayList<>(found);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Steps 2-4: from a file page address to the final, signed download address. */
    String resolve(String filePage) throws IOException {
        Document page = Http.url(filePage).referrer(url.toExternalForm()).get();
        Element link = page.selectFirst("a[href*=/file/]");
        String downloadPage = link == null ? null : link.absUrl("href");
        if (downloadPage == null || downloadPage.isEmpty()) {
            throw new IOException("No Download link found on " + filePage);
        }
        Matcher idm = Pattern.compile("^(https?://[^/]+)/file/([^/?#]+)").matcher(downloadPage);
        if (!idm.find()) {
            throw new IOException("Unexpected download link " + downloadPage);
        }
        String base = idm.group(1);
        String id = idm.group(2);

        Http api = Http.url(base + "/api/_001_v2").referrer(downloadPage).ignoreContentType();
        api.header("Content-Type", "application/json");
        api.header("Accept", "application/json");
        api.connection().requestBody(new JSONObject().put("id", id).toString()).method(Connection.Method.POST);
        JSONObject meta = api.getJSON();
        String media = meta.optString("mediafiles", "");
        String path = meta.optString("path", "");
        if (media.isEmpty() || path.isEmpty()) {
            throw new IOException("Bunkr gave no file address for id " + id + ": " + meta.toString().substring(0, Math.min(300, meta.toString().length())));
        }
        String original = meta.optString("original", "");
        String raw = media + path;
        if (!original.isEmpty()) {
            raw += (raw.contains("?") ? "&" : "?") + "n=" + encode(original);
        }
        String signPath = new URL(raw).getPath();
        signPath = java.net.URLDecoder.decode(signPath.replace("+", "%2B"), StandardCharsets.UTF_8);
        JSONObject sig = Http.url(SIGN_SERVICE + "?path=" + encode(signPath)).ignoreContentType().getJSON();
        String token = sig.optString("token", "");
        if (token.isEmpty()) {
            throw new IOException("Bunkr's signing service gave no token for " + signPath);
        }
        return raw + (raw.contains("?") ? "&" : "?") + "token=" + encode(token) + "&ex=" + sig.opt("ex");
    }

    @Override
    protected void downloadURL(URL url, int index) {
        try {
            if (index > 0) {
                sleep(500);
            }
            String finalUrl = resolve(url.toExternalForm());
            String name = null;
            Matcher n = Pattern.compile("[?&]n=([^&]+)").matcher(finalUrl);
            if (n.find()) {
                name = java.net.URLDecoder.decode(n.group(1), StandardCharsets.UTF_8);
            }
            Map<String, String> options = new LinkedHashMap<>();
            options.put("prefix", getPrefix(index));
            options.put("referrer", url.getProtocol() + "://" + url.getHost() + "/");
            if (name != null && !name.isEmpty()) {
                int dot = name.lastIndexOf('.');
                String base = (dot > 0 ? name.substring(0, dot) : name).replaceAll("[\\\\/:*?\"<>|]", "_");
                options.put("fileName", base);
                if (dot > 0 && dot < name.length() - 1) {
                    options.put("extension", name.substring(dot + 1));
                }
            }
            addURLToDownload(new URL(finalUrl), options);
        } catch (IOException e) {
            logger.error("Could not get the download address for " + url + ": " + e.getMessage());
        }
    }
}
