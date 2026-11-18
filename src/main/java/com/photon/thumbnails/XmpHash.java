package com.photon.thumbnails;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zapis sumy SHA-256 oryginalnego zdjęcia w metadanych XMP miniatury.
 * <p>
 * Właściwość: {@code photon:OriginalSHA256} w przestrzeni nazw {@value #NAMESPACE}.
 * ExifTool pokazuje ją jako {@code XMP-photon:OriginalSHA256}.
 * Istniejący pakiet XMP oryginału jest zachowany — dopisujemy tylko tę jedną właściwość.
 */
public final class XmpHash {

    public static final String NAMESPACE = "https://ns.photon.local/1.0/";
    public static final String PREFIX = "photon";
    public static final String PROPERTY = "OriginalSHA256";

    private static final Pattern DESCRIPTION = Pattern.compile("<rdf:Description(?=[\\s>/])");
    private static final Pattern OLD_ATTRIBUTE = Pattern.compile(
            "\\s+(?:xmlns:" + PREFIX + "|" + PREFIX + ":" + PROPERTY + ")\\s*=\\s*(\"[^\"]*\"|'[^']*')");
    private static final Pattern OLD_ELEMENT = Pattern.compile(
            "\\s*<" + PREFIX + ":" + PROPERTY + ">[^<]*</" + PREFIX + ":" + PROPERTY + ">");
    private static final Pattern PADDING = Pattern.compile("\\s{64,}(?=<\\?xpacket end)");

    private XmpHash() {
    }

    /** Nowy pakiet XMP zawierający tylko sumę oryginału. */
    static String newPacket(String sha256) {
        return "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
                + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n"
                + " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
                + "  <rdf:Description rdf:about=\"\"" + attributes(sha256) + "/>\n"
                + " </rdf:RDF>\n"
                + "</x:xmpmeta>\n"
                + "<?xpacket end=\"w\"?>";
    }

    /**
     * Dopisuje (albo podmienia) sumę w istniejącym pakiecie XMP.
     * Nadmiarowe wypełnienie spacjami (miejsce na edycję „w miejscu”) jest usuwane,
     * żeby pakiet zmieścił się w segmencie JPEG.
     */
    static String withHash(String packet, String sha256) {
        String cleaned = OLD_ELEMENT.matcher(OLD_ATTRIBUTE.matcher(packet).replaceAll("")).replaceAll("");
        Matcher description = DESCRIPTION.matcher(cleaned);
        if (!description.find()) {
            return newPacket(sha256); // brak rdf:Description — nieczytelny pakiet, tworzymy nowy
        }
        String result = cleaned.substring(0, description.end()) + attributes(sha256)
                + cleaned.substring(description.end());
        return PADDING.matcher(result).replaceFirst("\n");
    }

    private static String attributes(String sha256) {
        return " xmlns:" + PREFIX + "=\"" + NAMESPACE + "\" " + PREFIX + ":" + PROPERTY + "=\"" + sha256 + "\"";
    }
}
