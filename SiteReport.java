import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Reads the JUnit results of "gradlew testLive" and writes site-report-live.txt: which sites work and,
 * for the ones that don't, whether the cause looks like Cloudflare/bot blocking, a server (PHP/5xx) error,
 * a dead site or test link, or a ripper that needs fixing.
 *
 * Run from the project folder:  java SiteReport.java
 */
public class SiteReport {

    private static final String WORKING = "WORKING";
    private static final String[] ORDER = {
            "BLOCKED (403): Cloudflare / bot protection, or a login is required",
            "LOGIN NEEDED (401)",
            "RATE LIMITED (429): too many requests",
            "SERVER ERROR (5xx): the site, its PHP backend or Cloudflare is having a problem",
            "SITE GONE: the domain no longer resolves",
            "CANNOT CONNECT: site down, blocked or timing out",
            "PAGE GONE (404/410): the test's sample link is dead",
            "RIPPER FINDS NOTHING: page layout probably changed - the ripper needs fixing",
            "TOO SLOW: test took longer than 150 seconds",
            "OTHER: read the message" };

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get("build", "test-results", "testLive");
        if (!Files.isDirectory(dir)) {
            System.err.println("No results in " + dir.toAbsolutePath() + "\nRun check-sites.bat first.");
            System.exit(1);
        }
        File[] xmls = dir.toFile().listFiles((d, n) -> n.endsWith(".xml"));
        if (xmls == null || xmls.length == 0) {
            System.err.println("No result files in " + dir.toAbsolutePath());
            System.exit(1);
        }

        List<String> working = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Map<String, List<String>> failed = new LinkedHashMap<>();
        for (String category : ORDER) {
            failed.put(category, new ArrayList<>());
        }

        for (File xml : xmls) {
            var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml);
            NodeList cases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength(); i++) {
                Element tc = (Element) cases.item(i);
                String name = tc.getAttribute("classname").replaceAll("^.*\\.", "") + "." + tc.getAttribute("name");
                Element bad = firstChild(tc, "failure");
                if (bad == null) {
                    bad = firstChild(tc, "error");
                }
                if (bad != null) {
                    String text = bad.getAttribute("message") + "\n" + bad.getTextContent();
                    String message = bad.getAttribute("message").replaceAll("\\s+", " ");
                    if (message.length() > 260) {
                        message = message.substring(0, 260) + "...";
                    }
                    String category = classify(text);
                    String note = mentionsCloudflare(text) ? "  [mentions Cloudflare]" : "";
                    failed.get(category).add(name + "  -  " + message + note);
                } else if (firstChild(tc, "skipped") != null) {
                    skipped.add(name);
                } else {
                    working.add(name);
                }
            }
        }

        int failedCount = failed.values().stream().mapToInt(List::size).sum();
        StringBuilder out = new StringBuilder();
        out.append("RipMe live site report - ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("\n");
        out.append("Working: ").append(working.size()).append("   Not working: ").append(failedCount)
                .append("   Still skipped: ").append(skipped.size()).append("\n\n");
        out.append("=== SUMMARY OF WHAT IS NOT WORKING ===\n");
        for (String category : ORDER) {
            if (!failed.get(category).isEmpty()) {
                out.append(String.format("%3d  %s%n", failed.get(category).size(), category));
            }
        }
        out.append("\n");
        for (String category : ORDER) {
            List<String> list = failed.get(category);
            if (list.isEmpty()) {
                continue;
            }
            Collections.sort(list);
            out.append("=== ").append(category).append(" ===\n");
            list.forEach(l -> out.append(l).append("\n"));
            out.append("\n");
        }
        Collections.sort(working);
        Collections.sort(skipped);
        out.append("=== ").append(WORKING).append(" (").append(working.size()).append(") ===\n");
        working.forEach(l -> out.append(l).append("\n"));
        out.append("\n=== STILL SKIPPED (").append(skipped.size()).append(") ===\n");
        skipped.forEach(l -> out.append(l).append("\n"));

        Files.writeString(Paths.get("site-report-live.txt"), out.toString(), StandardCharsets.UTF_8);
        System.out.println(out.substring(0, out.indexOf("\n\n", out.indexOf("=== SUMMARY"))));
        System.out.println("\nFull details: site-report-live.txt");
    }

    private static boolean mentionsCloudflare(String text) {
        return Pattern.compile("cloudflare|cf-ray|Just a moment|Attention Required", Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    static String classify(String text) {
        if (has(text, "Status( Code |=)403")) {
            return ORDER[0];
        }
        if (has(text, "Status( Code |=)401")) {
            return ORDER[1];
        }
        if (has(text, "Status( Code |=)429")) {
            return ORDER[2];
        }
        if (has(text, "Status( Code |=)5\\d\\d")) {
            return ORDER[3];
        }
        if (has(text, "UnknownHostException")) {
            return ORDER[4];
        }
        if (has(text, "SocketTimeoutException|ConnectException|Connection refused|Connection reset|SSLHandshake|SSLException|after \\d+ attempts")) {
            return ORDER[5];
        }
        if (has(text, "Status( Code |=)(404|410)|File not found")) {
            return ORDER[6];
        }
        if (has(text, "No images found|No album|No images|Album or user doesn")) {
            return ORDER[7];
        }
        if (has(text, "TimeoutException|timed out after")) {
            return ORDER[8];
        }
        return ORDER[9];
    }

    private static boolean has(String text, String regex) {
        return Pattern.compile(regex).matcher(text).find();
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }
}
