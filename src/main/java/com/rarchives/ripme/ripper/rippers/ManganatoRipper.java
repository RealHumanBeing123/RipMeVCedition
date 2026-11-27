package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.nodes.Document;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.utils.Http;

/**
 * MangaNato (manganato.gg). A series link (manganato.gg/manga/name) rips every chapter into its own folder;
 * a chapter link (manganato.gg/manga/name/chapter-12) rips that chapter only.
 *
 * A chapter page holds two script lines: var cdns = [...] (picture servers) and var chapterImages = [...]
 * (paths such as "name/12/0.webp"). The chapter list of a series is loaded by the page from
 * /api/manga/NAME/chapters, so that is read directly.
 */
public class ManganatoRipper extends AbstractHTMLRipper {

    private static final Logger logger = LogManager.getLogger(ManganatoRipper.class);

    private static final Pattern LINK = Pattern.compile(
            "^https?://(?:www\\.)?manganato\\.gg/manga/([A-Za-z0-9._-]+)(?:/(chapter-[A-Za-z0-9._-]+))?(?:[/?#].*)?$");
    private static final Pattern CHAPTER_SLUG = Pattern.compile("(?:^|/)(chapter-[0-9][A-Za-z0-9._-]*)/?$");
    private static final String SITE = "https://www.manganato.gg";

    public ManganatoRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "manganato";
    }

    @Override
    protected String getDomain() {
        return "manganato.gg";
    }

    @Override
    public boolean canRip(URL url) {
        return LINK.matcher(url.toExternalForm()).matches();
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Matcher m = LINK.matcher(url.toExternalForm());
        if (!m.matches()) {
            throw new MalformedURLException("Expected manganato.gg/manga/name or manganato.gg/manga/name/chapter-N - got " + url);
        }
        return m.group(2) == null ? m.group(1) : m.group(1) + "_" + m.group(2);
    }

    @Override
    public Document getFirstPage() throws IOException {
        return Http.url(url).referrer(SITE + "/").get();
    }

    @Override
    public Document getNextPage(Document doc) throws IOException {
        throw new IOException("No more pages");
    }

    /** The picture addresses of one chapter page, or an empty list when the page has none. */
    static List<String> imagesOf(String html) {
        List<String> urls = new ArrayList<>();
        String cdn = null;
        JSONArray cdns = arrayAfter(html, "var cdns");
        if (cdns != null && cdns.length() > 0) {
            cdn = cdns.optString(0, null);
        }
        JSONArray images = arrayAfter(html, "var chapterImages");
        if (cdn == null || images == null) {
            return urls;
        }
        String base = cdn.trim().replaceAll("/+$", "");
        for (int i = 0; i < images.length(); i++) {
            String path = images.optString(i, "").trim().replaceAll("^/+", "");
            if (!path.isEmpty()) {
                urls.add(base + "/" + path);
            }
        }
        return urls;
    }

    /** Reads the JSON array written after e.g. "var cdns =" in the page's script. */
    static JSONArray arrayAfter(String html, String name) {
        int at = html.indexOf(name);
        if (at < 0) {
            return null;
        }
        int open = html.indexOf('[', at);
        int eq = html.indexOf('=', at);
        if (open < 0 || eq < 0 || open < eq || open - eq > 5) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < html.length(); i++) {
            char c = html.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']' && --depth == 0) {
                try {
                    return new JSONArray(html.substring(open, i + 1));
                } catch (JSONException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Finds every "chapter-N" name anywhere in the chapter list answer, whatever its exact layout. */
    static List<String> chapterSlugs(String body) {
        Set<String> found = new LinkedHashSet<>();
        try {
            collect(body.trim().startsWith("[") ? new JSONArray(body) : new JSONObject(body), found);
        } catch (JSONException e) {
            // not JSON: fall through to the text search
        }
        if (found.isEmpty()) {
            Matcher m = Pattern.compile("(chapter-[0-9][A-Za-z0-9._-]*)").matcher(body);
            while (m.find()) {
                found.add(m.group(1));
            }
        }
        List<String> list = new ArrayList<>(found);
        list.sort((a, b) -> Double.compare(number(a), number(b)));
        return list;
    }

    private static void collect(Object node, Set<String> out) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            for (String key : o.keySet()) {
                collect(o.opt(key), out);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                collect(a.opt(i), out);
            }
        } else if (node instanceof String) {
            Matcher m = CHAPTER_SLUG.matcher((String) node);
            if (m.find()) {
                out.add(m.group(1));
            }
        }
    }

    /** chapter-12-5 -> 12.5, for sorting. */
    static double number(String slug) {
        Matcher m = Pattern.compile("chapter-(\\d+)(?:[-.](\\d+))?").matcher(slug);
        if (!m.find()) {
            return Double.MAX_VALUE;
        }
        return Double.parseDouble(m.group(1) + (m.group(2) != null ? "." + m.group(2) : ""));
    }

    private List<String> chapterList(String slug, Document page) throws IOException {
        String api = SITE + "/api/manga/" + slug + "/chapters";
        String body = "";
        try {
            body = Http.url(api).referrer(SITE + "/manga/" + slug).header("Accept", "application/json, text/plain, */*")
                    .ignoreContentType().response().body();
        } catch (IOException e) {
            logger.warn("Could not read the chapter list " + api, e);
        }
        List<String> slugs = chapterSlugs(body);
        if (slugs.isEmpty()) {
            // maybe the series page itself already lists them
            slugs = chapterSlugs(page.select("a[href*=/manga/" + slug + "/chapter-]").outerHtml());
        }
        if (slugs.isEmpty()) {
            String snippet = body.length() > 300 ? body.substring(0, 300) : body;
            throw new IOException("MangaNato gave no chapter list from " + api + ". Answer began: " + snippet);
        }
        return slugs;
    }

    @Override
    public List<String> getURLsFromPage(Document page) {
        List<String> urls = new ArrayList<>();
        Matcher m = LINK.matcher(url.toExternalForm());
        if (!m.matches()) {
            return urls;
        }
        String slug = m.group(1);
        List<String> chapters = new ArrayList<>();
        Document first = null;
        if (m.group(2) != null) {
            chapters.add(m.group(2));
            first = page;
        } else {
            try {
                chapters = chapterList(slug, page);
            } catch (IOException e) {
                logger.error(e.getMessage());
                return urls;
            }
        }
        boolean firstRequest = true;
        for (String chapter : chapters) {
            if (isStopped()) {
                break;
            }
            try {
                String html;
                if (first != null) {
                    html = first.outerHtml();
                    first = null;
                } else {
                    if (!firstRequest) {
                        sleep(800);
                    }
                    html = Http.url(SITE + "/manga/" + slug + "/" + chapter).referrer(SITE + "/manga/" + slug).get().outerHtml();
                }
                firstRequest = false;
                List<String> images = imagesOf(html);
                if (images.isEmpty()) {
                    logger.warn("No pictures found in " + slug + " " + chapter);
                }
                urls.addAll(images);
            } catch (IOException e) {
                logger.warn("Could not load " + slug + " " + chapter, e);
            }
        }
        return urls;
    }

    @Override
    protected void downloadURL(URL url, int index) {
        Map<String, String> options = new LinkedHashMap<>();
        // .../name/200/3.webp -> folder "chapter-200", file 003
        Matcher c = Pattern.compile("/([^/]+)/([^/]+)/(\\d+)\\.[A-Za-z0-9]+$").matcher(url.toExternalForm());
        if (c.find()) {
            options.put("subdirectory", "chapter-" + c.group(2));
            options.put("prefix", String.format("%03d_", Integer.parseInt(c.group(3)) + 1));
        } else {
            options.put("prefix", getPrefix(index));
        }
        options.put("referrer", SITE + "/");
        addURLToDownload(url, options);
    }
}
