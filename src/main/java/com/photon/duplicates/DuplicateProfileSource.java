package com.photon.duplicates;

import com.photon.profiles.ProfileItem;
import com.photon.profiles.ProfileSource;
import com.photon.ui.Section;
import com.photon.ui.Texts;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Profile modułu „Szukaj duplikatów” widziane przez wspólną listę profili.
 */
public class DuplicateProfileSource implements ProfileSource {

    private final DuplicateProfileStore store;

    public DuplicateProfileSource(DuplicateProfileStore store) {
        this.store = store;
    }

    @Override
    public Section module() {
        return Section.DUPLICATES;
    }

    @Override
    public List<ProfileItem> loadAll() throws IOException {
        return store.loadAll().stream()
                .map(p -> new ProfileItem(Section.DUPLICATES, p.name(), p.updatedAt(), describe(p.options())))
                .toList();
    }

    @Override
    public void delete(ProfileItem item) throws IOException {
        store.delete(new DuplicateProfile(item.name(), item.updatedAt(), null));
    }

    /** Krótki opis ustawień, np. „2 lokalizacje • 24 rozszerzenia • Identyczne pliki”. */
    static String describe(DuplicateSearchOptions o) {
        List<String> parts = new ArrayList<>();
        int locations = o.locations() == null ? 0 : o.locations().size();
        int extensions = o.extensions() == null ? 0 : o.extensions().size();
        parts.add(Texts.count(locations, "lokalizacja", "lokalizacje", "lokalizacji"));
        parts.add(Texts.count(extensions, "rozszerzenie", "rozszerzenia", "rozszerzeń"));
        if (o.method() != null) {
            parts.add(o.method() == SearchMethod.SIMILAR
                    ? o.method().getTitle() + " (" + o.similarityThreshold() + "%)"
                    : o.method().getTitle());
        }
        if (o.action() != null && o.action() != DuplicateAction.REPORT_ONLY) {
            parts.add(o.action() == DuplicateAction.MOVE_TO_TRASH ? "do kosza" : "przenoszenie do folderu");
        }
        return String.join("  •  ", parts);
    }
}
