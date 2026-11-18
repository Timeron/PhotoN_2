package com.photon.thumbnails;

import com.photon.AppPaths;
import com.photon.profiles.ProfileItem;
import com.photon.profiles.ProfileSource;
import com.photon.profiles.ProfileStore;
import com.photon.ui.Section;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Profile miniaturyzacji (pliki JSON w {@code profiles/thumbnails}).
 */
public class ThumbnailProfileStore extends ProfileStore<ThumbnailOptions> {

    public ThumbnailProfileStore(Path directory) {
        super(directory, ThumbnailOptions.class);
    }

    public static ThumbnailProfileStore createDefault() {
        return new ThumbnailProfileStore(AppPaths.profilesDir("thumbnails"));
    }

    /** Profile miniaturyzacji widziane przez wspólną listę w sekcji „Profile”. */
    public ProfileSource asSource() {
        return new ProfileSource() {
            @Override
            public Section module() {
                return Section.THUMBNAILS;
            }

            @Override
            public List<ProfileItem> loadAll() throws IOException {
                return ThumbnailProfileStore.this.loadAll().stream()
                        .map(p -> new ProfileItem(Section.THUMBNAILS, p.name(), p.updatedAt(), describe(p.options())))
                        .toList();
            }

            @Override
            public void delete(ProfileItem item) throws IOException {
                ThumbnailProfileStore.this.delete(item.name());
            }
        };
    }

    /** Krótki opis, np. „800 px  •  Zdjecia → Miniatury  •  z podfolderami”. */
    static String describe(ThumbnailOptions o) {
        List<String> parts = new ArrayList<>();
        parts.add(o.maxSide() + " px");
        if (o.source() != null || o.target() != null) {
            parts.add(folderName(o.source()) + " → " + folderName(o.target()));
        }
        parts.add(o.includeSubfolders() ? "z podfolderami" : "bez podfolderów");
        if (o.overwrite()) {
            parts.add("zastępowanie istniejących");
        }
        return String.join("  •  ", parts);
    }

    private static String folderName(Path path) {
        if (path == null) {
            return "—";
        }
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }
}
