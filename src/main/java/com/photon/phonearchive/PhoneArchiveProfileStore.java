package com.photon.phonearchive;

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
 * Profile archiwizacji telefonu (pliki JSON w {@code profiles/phone-archive}).
 */
public class PhoneArchiveProfileStore extends ProfileStore<PhoneArchiveOptions> {

    public PhoneArchiveProfileStore(Path directory) {
        super(directory, PhoneArchiveOptions.class);
    }

    public static PhoneArchiveProfileStore createDefault() {
        return new PhoneArchiveProfileStore(AppPaths.profilesDir("phone-archive"));
    }

    /** Profile archiwizacji widziane przez wspólną listę w sekcji „Profile”. */
    public ProfileSource asSource() {
        return new ProfileSource() {
            @Override
            public Section module() {
                return Section.PHONE_ARCHIVE;
            }

            @Override
            public List<ProfileItem> loadAll() throws IOException {
                return PhoneArchiveProfileStore.this.loadAll().stream()
                        .map(p -> new ProfileItem(Section.PHONE_ARCHIVE, p.name(), p.updatedAt(), describe(p.options())))
                        .toList();
            }

            @Override
            public void delete(ProfileItem item) throws IOException {
                PhoneArchiveProfileStore.this.delete(item.name());
            }
        };
    }

    /** Krótki opis, np. „Camera → Archiwum  •  z podfolderami”. */
    public static String describe(PhoneArchiveOptions o) {
        List<String> parts = new ArrayList<>();
        parts.add(folderName(o.source()) + " → " + folderName(o.target()));
        parts.add(o.includeSubfolders() ? "z podfolderami" : "bez podfolderów");
        if (o.movesDuplicates()) {
            parts.add("duplikaty → śmietnik " + folderName(o.duplicatesTarget()));
        }
        if (o.makeThumbnails()) {
            parts.add("miniatury " + o.thumbnailSide() + " px → " + folderName(o.thumbnailTarget()));
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
