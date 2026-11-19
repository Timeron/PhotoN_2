package com.photon.help;

import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Instrukcja ({@code INSTRUKCJA.md}) przygotowana do pokazania w aplikacji: bloki dokumentu, spis treści,
 * kotwice odnośników (jak na GitHubie) i wyszukiwanie bez względu na wielkość liter i polskie znaki.
 */
public final class HelpDocument {

    /** Zasób z instrukcją (kopiowany przy budowaniu z głównego folderu projektu). */
    public static final String RESOURCE = "/com/photon/help/INSTRUKCJA.md";

    /** Pozycja spisu treści. */
    public record TocEntry(String id, String title, int level, int block) {
    }

    /**
     * Wynik wyszukiwania.
     *
     * @param block   numer bloku dokumentu
     * @param section tytuł rozdziału, w którym leży blok
     * @param snippet fragment tekstu wokół trafienia
     */
    public record Hit(int block, String section, String snippet) {
    }

    private static final Pattern HTML_ID = Pattern.compile("id\\s*=\\s*\"([^\"]+)\"");
    private static final int SNIPPET = 60;

    private final List<Node> blocks = new ArrayList<>();
    private final List<String> texts = new ArrayList<>();
    private final List<String> searchable = new ArrayList<>();
    private final List<TocEntry> toc = new ArrayList<>();
    private final Map<String, Integer> anchors = new LinkedHashMap<>();
    private final String title;

    private HelpDocument(String markdown) {
        Node document = Parser.builder().extensions(List.of(TablesExtension.create())).build().parse(markdown);
        String docTitle = "Instrukcja";
        for (Node child = document.getFirstChild(); child != null; child = child.getNext()) {
            int index = blocks.size();
            blocks.add(child);
            String text = plainText(child);
            texts.add(text);
            searchable.add(normalize(text));
            if (child instanceof Heading heading) {
                String id = slug(text);
                anchors.putIfAbsent(id, index);
                if (heading.getLevel() == 1) {
                    docTitle = text;
                } else {
                    toc.add(new TocEntry(id, text, heading.getLevel(), index));
                }
            }
            // Kotwice wstawione ręcznie: <a id="…"></a>
            child.accept(new AbstractVisitor() {
                @Override
                public void visit(HtmlInline html) {
                    Matcher m = HTML_ID.matcher(html.getLiteral());
                    if (m.find()) {
                        anchors.putIfAbsent(m.group(1), index);
                    }
                }
            });
        }
        this.title = docTitle;
    }

    /** Instrukcja z zasobów aplikacji. */
    public static HelpDocument load() throws IOException {
        try (InputStream in = HelpDocument.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("Brak instrukcji w programie (" + RESOURCE + ").");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    public static HelpDocument parse(String markdown) {
        return new HelpDocument(markdown);
    }

    public String title() {
        return title;
    }

    /** Bloki najwyższego poziomu (nagłówki, akapity, listy, tabele, kod…). */
    public List<Node> blocks() {
        return List.copyOf(blocks);
    }

    /** Czysty tekst bloku. */
    public String text(int block) {
        return texts.get(block);
    }

    public List<TocEntry> toc() {
        return List.copyOf(toc);
    }

    /** Blok, do którego prowadzi odnośnik {@code #kotwica} (albo -1). */
    public int anchor(String id) {
        String key = id.startsWith("#") ? id.substring(1) : id;
        return anchors.getOrDefault(key, anchors.getOrDefault(slug(key), -1));
    }

    /** Odnośniki wewnątrz dokumentu ({@code #…}), które nie prowadzą do żadnego miejsca. */
    public List<String> brokenLinks() {
        List<String> broken = new ArrayList<>();
        for (Node block : blocks) {
            block.accept(new AbstractVisitor() {
                @Override
                public void visit(Link link) {
                    String target = link.getDestination();
                    if (target.startsWith("#") && anchor(target) < 0) {
                        broken.add(target);
                    }
                    visitChildren(link);
                }
            });
        }
        return broken;
    }

    /** Rozdział (najbliższy nagłówek przed blokiem). */
    public String sectionOf(int block) {
        String section = title;
        for (TocEntry entry : toc) {
            if (entry.block() > block) {
                break;
            }
            section = entry.title();
        }
        return section;
    }

    /**
     * Bloki zawierające wszystkie słowa zapytania (bez względu na wielkość liter i polskie znaki:
     * „zrodlo” znajdzie „źródło”).
     */
    public List<Hit> search(String query) {
        List<String> words = List.of(normalize(query).trim().split("\\s+"));
        if (words.isEmpty() || words.get(0).isEmpty()) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            String haystack = searchable.get(i);
            if (words.stream().allMatch(haystack::contains)) {
                hits.add(new Hit(i, sectionOf(i), snippet(i, haystack.indexOf(words.get(0)))));
            }
        }
        return hits;
    }

    private String snippet(int block, int at) {
        String text = texts.get(block).replace('\n', ' ');
        int start = Math.max(0, at - SNIPPET / 2);
        int end = Math.min(text.length(), at + SNIPPET);
        return (start > 0 ? "…" : "") + text.substring(start, end).trim() + (end < text.length() ? "…" : "");
    }

    // =====================================================================
    // Pomocnicze
    // =====================================================================

    /** Tekst węzła bez formatowania (kod w `…` jako zwykły tekst). */
    static String plainText(Node node) {
        StringBuilder text = new StringBuilder();
        node.accept(new AbstractVisitor() {
            @Override
            public void visit(Text t) {
                text.append(t.getLiteral());
            }

            @Override
            public void visit(Code code) {
                text.append(code.getLiteral());
            }

            @Override
            public void visit(SoftLineBreak br) {
                text.append(' ');
            }

            @Override
            public void visit(FencedCodeBlock block) {
                text.append(block.getLiteral());
            }

            @Override
            public void visit(Paragraph paragraph) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                visitChildren(paragraph);
            }
        });
        return text.toString().trim();
    }

    /** Kotwica nagłówka jak na GitHubie: „5.5a. Uruchamianie (`autostart.bat`)” → „55a-uruchamianie-autostartbat”. */
    static String slug(String heading) {
        StringBuilder slug = new StringBuilder();
        for (char c : heading.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                slug.append(c);
            } else if (c == ' ') {
                slug.append('-');
            }
        }
        return slug.toString();
    }

    /** Małe litery bez polskich znaków (do wyszukiwania). */
    static String normalize(String text) {
        String lower = text.toLowerCase(Locale.ROOT).replace('ł', 'l');
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }
}
