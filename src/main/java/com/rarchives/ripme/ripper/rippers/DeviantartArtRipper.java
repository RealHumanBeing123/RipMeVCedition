package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.rarchives.ripme.ripper.AbstractHTMLRipper;
import com.rarchives.ripme.utils.Http;

/**
 * Rips a single DeviantArt artwork page, e.g.
 * https://www.deviantart.com/artist/art/Some-Title-1020701055
 *
 * The page contains all its data as JSON inside window.__INITIAL_STATE__; the picture
 * address is built from that data.
 */
public class DeviantartArtRipper extends AbstractHTMLRipper {

    // static: the constructor of AbstractRipper calls getGID before instance fields exist
    private static final Pattern ART = Pattern.compile(
            "^https?://(?:www\\.)?deviantart\\.com/([A-Za-z0-9_-]+)/art/([^/?#]*?)-?([0-9]+)/?(?:[?#].*)?$");

    public DeviantartArtRipper(URL url) throws IOException {
        super(url);
    }

    @Override
    public String getHost() {
        return "deviantart";
    }

    @Override
    protected String getDomain() {
        return "deviantart.com";
    }

    @Override
    public boolean canRip(URL url) {
        // The ripper factory picks the first ripper whose canRip says yes, so match the address shape, not just the site.
        return ART.matcher(url.toExternalForm()).matches();
    }

    @Override
    public boolean canRip(URL url) {
        return ART.matcher(url.toExternalForm()).matches();
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Matcher m = ART.matcher(url.toExternalForm());
        if (m.matches()) {
            return m.group(1) + "_art_" + m.group(3);
        }
        throw new MalformedURLException("Expected deviantart.com/<artist>/art/<title>-<number> - got " + url);
    }

    @Override
    protected Document getFirstPage() throws IOException, URISyntaxException {
        return Http.url(url).get();
    }

    /**
     * Returns the text of the string literal in  __INITIAL_STATE__ = JSON.parse("...")  (still escaped),
     * or null. Done by hand because the literal is about 1 MB long, which overflows regular expressions.
     */
    public static String stateLiteral(String script) {
        int at = script.indexOf("__INITIAL_STATE__");
        if (at < 0) {
            return null;
        }
        String marker = "JSON.parse(\"";
        int start = script.indexOf(marker, at);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        for (int i = start; i < script.length(); i++) {
            char c = script.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return script.substring(start, i);
            }
        }
        return null;
    }

    /** Turns the JavaScript string literal inside JSON.parse("...") into the JSON text it holds. */
    public static String unescapeJsString(String raw) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) {
                out.append(c);
                continue;
            }
            char n = raw.charAt(++i);
            switch (n) {
                case 'n': out.append('\n'); break;
                case 't': out.append('\t'); break;
                case 'r': out.append('\r'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'x':
                    out.append((char) Integer.parseInt(raw.substring(i + 1, i + 3), 16));
                    i += 2;
                    break;
                case 'u':
                    out.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                    i += 4;
                    break;
                default: out.append(n); // \" \\ \' \/
            }
        }
        return out.toString();
    }

    /** Finds the best picture address for the artwork with the given id in the page's JSON data. */
    static String pictureFromState(JSONObject state, String id) {
        JSONObject deviations = state.optJSONObject("@@entities") == null ? null
                : state.getJSONObject("@@entities").optJSONObject("deviation");
        if (deviations == null) {
            return null;
        }
        return pictureFromDeviation(deviations.optJSONObject(id));
    }

    /** Best picture address for one deviation object (from the page data or the gallery API). */
    public static String pictureFromDeviation(JSONObject dev) {
        if (dev == null) {
            return null;
        }
        // A downloadable artwork can offer the original file directly.
        JSONObject download = dev.optJSONObject("download");
        if (download != null && download.optString("url", "").startsWith("http")) {
            return download.getString("url");
        }
        JSONObject media = dev.optJSONObject("media");
        if (media == null) {
            return null;
        }
        String base = media.optString("baseUri", "");
        if (base.isEmpty()) {
            return null;
        }
        String pretty = media.optString("prettyName", "");
        JSONArray tokens = media.optJSONArray("token");
        String token = tokens != null && tokens.length() > 0 ? tokens.optString(0, "") : "";
        String path = null;
        JSONArray types = media.optJSONArray("types");
        if (types != null) {
            // Prefer the largest full-size rendition.
            for (String wanted : new String[] { "fullview", "preview" }) {
                for (int i = 0; i < types.length(); i++) {
                    JSONObject t = types.getJSONObject(i);
                    if (wanted.equals(t.optString("t")) && t.has("c")) {
                        path = t.getString("c").replace("<prettyName>", pretty);
                        break;
                    }
                    if (wanted.equals(t.optString("t")) && t.optString("b", "").startsWith("http")) {
                        return t.getString("b");
                    }
                }
                if (path != null) {
                    break;
                }
            }
        }
        String result = path == null ? base : base + path;
        if (!token.isEmpty() && !result.contains("token=")) {
            result += (result.contains("?") ? "&" : "?") + "token=" + token;
        }
        return result;
    }

    @Override
    protected List<String> getURLsFromPage(Document page) {
        List<String> result = new ArrayList<>();
        Matcher idMatcher = ART.matcher(url.toExternalForm());
        String id = idMatcher.matches() ? idMatcher.group(3) : "";
        for (Element script : page.select("script")) {
            String data = script.data();
            String literal = stateLiteral(data);
            if (literal == null) {
                continue;
            }
            JSONObject state = new JSONObject(unescapeJsString(literal));
            String pic = pictureFromState(state, id);
            if (pic != null) {
                result.add(pic);
            }
            break;
        }
        if (result.isEmpty()) {
            // Fall back to the preview picture the page advertises.
            Element og = page.selectFirst("meta[property=og:image]");
            if (og != null && !og.attr("content").isEmpty()) {
                result.add(og.attr("content"));
            }
        }
        return result;
    }

    @Override
    protected void downloadURL(URL url, int index) {
        addURLToDownload(url, getPrefix(index));
    }
}
