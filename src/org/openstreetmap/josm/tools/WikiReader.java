// License: GPL. Copyright 2007 by Immanuel Scholz and others
package org.openstreetmap.josm.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;

import org.openstreetmap.josm.Main;

/**
 * Read a trac-wiki page.
 *
 * @author imi
 */
public class WikiReader {

    private final String baseurl;

    public WikiReader(String baseurl) {
        this.baseurl = baseurl;
    }

    public WikiReader() {
        this.baseurl = Main.pref.get("help.baseurl", "http://josm.openstreetmap.de");
    }

    /**
     * Read the page specified by the url and return the content.
     *
     * If the url is within the baseurl path, parse it as an trac wikipage and replace relative
     * pathes etc..
     *
     * @throws IOException Throws, if the page could not be loaded.
     */
    public String read(String url) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(new URL(url).openStream(), "utf-8"));
        if (url.startsWith(baseurl) && !url.endsWith("?format=txt"))
            return readFromTrac(in);
        return readNormal(in);
    }

    public String readLang(String text) throws IOException {
        String languageCode = LanguageInfo.getWikiLanguagePrefix();
        String res = readLang(new URL(baseurl + "/wiki/" + languageCode + text));
        if (res.isEmpty() && !languageCode.isEmpty()) {
            res = readLang(new URL(baseurl + "/wiki/" + text));
        }
        if (res.isEmpty()) {
            throw new IOException(text + " does not exist");
        } else {
            return res;
        }
    }

    private String readLang(URL url) throws IOException {
        InputStream in = url.openStream();
        return readFromTrac(new BufferedReader(new InputStreamReader(in, "utf-8")));
    }

    private String readNormal(BufferedReader in) throws IOException {
        String b = "";
        for (String line = in.readLine(); line != null; line = in.readLine()) {
            if (!line.contains("[[TranslatedPages]]")) {
                b += line.replaceAll(" />", ">") + "\n";
            }
        }
        return "<html>" + b + "</html>";
    }

    private String readFromTrac(BufferedReader in) throws IOException {
        boolean inside = false;
        boolean transl = false;
        boolean skip = false;
        String b = "";
        for (String line = in.readLine(); line != null; line = in.readLine()) {
            if (line.contains("<div id=\"searchable\">")) {
                inside = true;
            } else if (line.contains("<div class=\"wiki-toc trac-nav\"")) {
                transl = true;
            } else if (line.contains("<div class=\"wikipage searchable\">")) {
                inside = true;
            } else if (line.contains("<div class=\"buttons\">")) {
                inside = false;
            } else if (line.contains("<h3>Attachments</h3>")) {
                inside = false;
            } else if (line.contains("<div id=\"attachments\">")) {
                inside = false;
            } else if (line.contains("<div class=\"trac-modifiedby\">")) {
                skip = true;
            }
            if (inside && !transl && !skip) {
                // add a border="0" attribute to images, otherwise the internal help browser
                // will render a thick  border around images inside an <a> element
                //
                b += line.replaceAll("<img src=\"/", "<img border=\"0\" src=\"" + baseurl + "/").replaceAll("href=\"/",
                        "href=\"" + baseurl + "/").replaceAll(" />", ">")
                        + "\n";
            } else if (transl && line.contains("</div>")) {
                transl = false;
            }
            if (line.contains("</div>")) {
                skip = false;
            }
        }
        if (b.indexOf("      Describe ") >= 0
        || b.indexOf(" does not exist. You can create it here.</p>") >= 0)
            return "";
        return "<html>" + b + "</html>";
    }
}
