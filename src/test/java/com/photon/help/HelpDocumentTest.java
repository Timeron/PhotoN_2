package com.photon.help;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Instrukcja w aplikacji: spis treści, odnośniki i wyszukiwanie — na prawdziwym INSTRUKCJA.md. */
class HelpDocumentTest {

    @Test
    void realManualIsBundledAndAllInternalLinksWork() throws IOException {
        HelpDocument doc = HelpDocument.load();
        assertTrue(doc.title().startsWith("PhotoN"), doc.title());
        List<String> titles = doc.toc().stream().map(HelpDocument.TocEntry::title).toList();
        assertTrue(titles.contains("5. Funkcje aplikacji"), titles.toString());
        assertTrue(titles.stream().anyMatch(t -> t.startsWith("5.5a.")), titles.toString());
        assertEquals(List.of(), doc.brokenLinks(), "każdy odnośnik #… w instrukcji prowadzi do istniejącego miejsca");
    }

    @Test
    void searchIgnoresCaseAndPolishCharacters() throws IOException {
        HelpDocument doc = HelpDocument.load();
        List<HelpDocument.Hit> hits = doc.search("ZRODLO");
        assertFalse(hits.isEmpty());
        assertTrue(hits.stream().anyMatch(h -> h.snippet().toLowerCase().contains("źródło")), hits.toString());
        List<HelpDocument.Hit> both = doc.search("autostart wlacz");
        assertFalse(both.isEmpty(), "wszystkie słowa w jednym bloku");
        assertTrue(both.stream().allMatch(h -> h.section().length() > 0));
        assertTrue(doc.search("słowo-którego-na-pewno-nie-ma").isEmpty());
        assertTrue(doc.search("   ").isEmpty());
    }

    @Test
    void anchorsLikeGitHub() {
        assertEquals("55a-uruchamianie-kopii-zapasowej-razem-z-windows-autostartbat",
                HelpDocument.slug("5.5a. Uruchamianie kopii zapasowej razem z Windows (autostart.bat)"));
        assertEquals("4-na-co-zwracać-uwagę", HelpDocument.slug("4. Na co zwracać uwagę"));

        HelpDocument doc = HelpDocument.parse("""
                # Tytuł

                ## 1. Pierwszy

                Zobacz [drugi](#2-drugi) i [kotwicę](#moja).

                ## 2. Drugi

                - punkt <a id="moja"></a>**ważny**
                - [zepsuty](#nie-ma)
                """);
        assertEquals("Tytuł", doc.title());
        assertEquals(2, doc.toc().size());
        assertEquals(doc.toc().get(1).block(), doc.anchor("#2-drugi"));
        assertTrue(doc.anchor("moja") > doc.toc().get(1).block(), "kotwica <a id> w liście");
        assertEquals(List.of("#nie-ma"), doc.brokenLinks());
        assertEquals("2. Drugi", doc.sectionOf(doc.anchor("moja")));
    }
}
