package com.photon.backup;

import com.photon.phonearchive.PhoneArchiveOptions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Profil „Archiwizacji telefonu” obsługiwany przez agenta. Agent może pilnować kilku profili naraz —
 * każdy ma swój folder źródłowy i swoje archiwum.
 */
public record AgentProfile(String name, PhoneArchiveOptions options) {

    /**
     * Sprawdza, czy profile mogą działać razem. Foldery źródłowe nie mogą się pokrywać (te same pliki byłyby
     * przetwarzane dwa razy), a żaden folder, do którego profil zapisuje (archiwum, miniatury, śmietnik),
     * nie może leżeć w folderze źródłowym innego profilu ani go zawierać (archiwizowałby cudze archiwum).
     *
     * @return opis problemu albo null
     */
    public static String validate(List<AgentProfile> profiles) {
        if (profiles.isEmpty()) {
            return "Wybierz co najmniej jeden profil.";
        }
        Set<String> names = new HashSet<>();
        for (AgentProfile profile : profiles) {
            if (!names.add(profile.name())) {
                return "Profil „" + profile.name() + "” wybrano więcej niż raz.";
            }
            String problem = profile.options().validate();
            if (problem != null) {
                return "Profil „" + profile.name() + "”: " + problem;
            }
        }
        for (int i = 0; i < profiles.size(); i++) {
            for (int j = 0; j < profiles.size(); j++) {
                if (i == j) {
                    continue;
                }
                AgentProfile a = profiles.get(i);
                AgentProfile b = profiles.get(j);
                Path source = normalize(a.options().source());
                if (i < j && overlap(source, normalize(b.options().source()))) {
                    return "Profile „" + a.name() + "” i „" + b.name() + "” mają pokrywające się foldery źródłowe ("
                            + a.options().source() + ", " + b.options().source() + ") — te same pliki byłyby "
                            + "archiwizowane dwa razy.";
                }
                for (Path written : b.writtenFolders()) {
                    if (overlap(source, normalize(written))) {
                        return "Folder źródłowy profilu „" + a.name() + "” (" + a.options().source() + ") pokrywa się "
                                + "z folderem, do którego zapisuje profil „" + b.name() + "” (" + written + ").";
                    }
                }
            }
        }
        return null;
    }

    /** Foldery, do których profil zapisuje: archiwum, miniatury i śmietnik na duplikaty. */
    List<Path> writtenFolders() {
        List<Path> folders = new ArrayList<>();
        folders.add(options.target());
        if (options.makeThumbnails() && options.thumbnailTarget() != null) {
            folders.add(options.thumbnailTarget());
        }
        if (options.movesDuplicates() && options.duplicatesTarget() != null) {
            folders.add(options.duplicatesTarget());
        }
        return folders;
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static boolean overlap(Path a, Path b) {
        return a.startsWith(b) || b.startsWith(a);
    }
}
