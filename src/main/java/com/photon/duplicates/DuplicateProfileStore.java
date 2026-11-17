package com.photon.duplicates;

import com.photon.AppPaths;
import com.photon.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Zapis i odczyt profili wyszukiwania duplikatów jako plików JSON
 * (jeden plik na profil, nazwa pliku = nazwa profilu).
 */
public class DuplicateProfileStore {

    /** Znaki niedozwolone w nazwie profilu (bo nazwa jest też nazwą pliku). */
    public static final String FORBIDDEN_CHARS = "\\/:*?\"<>|";
    public static final int MAX_NAME_LENGTH = 60;

    private static final String EXTENSION = ".json";

    private final Path directory;

    public DuplicateProfileStore(Path directory) {
        this.directory = directory;
    }

    public static DuplicateProfileStore createDefault() {
        return new DuplicateProfileStore(AppPaths.profilesDir("duplicates"));
    }

    public Path getDirectory() {
        return directory;
    }

    /** Wczytuje wszystkie profile posortowane po nazwie. Uszkodzone pliki są pomijane. */
    public List<DuplicateProfile> loadAll() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<DuplicateProfile> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(EXTENSION)).toList()) {
                try {
                    DuplicateProfile profile = Json.mapper().readValue(file.toFile(), DuplicateProfile.class);
                    if (profile.name() != null && profile.options() != null) {
                        result.add(profile);
                    }
                } catch (IOException e) {
                    System.err.println("Pominięto uszkodzony profil " + file + ": " + e.getMessage());
                }
            }
        }
        result.sort(Comparator.comparing(p -> p.name().toLowerCase(Locale.ROOT)));
        return result;
    }

    /** Zapisuje profil (nadpisuje istniejący o tej samej nazwie). Zapis jest atomowy. */
    public void save(DuplicateProfile profile) throws IOException {
        Json.writeAtomically(fileFor(profile.name()), profile);
    }

    /** Zmienia nazwę profilu: zapisuje go pod nową nazwą i usuwa stary plik. */
    public DuplicateProfile rename(DuplicateProfile profile, String newName) throws IOException {
        DuplicateProfile renamed = new DuplicateProfile(newName, profile.updatedAt(), profile.options());
        save(renamed);
        if (!fileFor(newName).equals(fileFor(profile.name()))) {
            Files.deleteIfExists(fileFor(profile.name()));
        }
        return renamed;
    }

    public void delete(DuplicateProfile profile) throws IOException {
        Files.deleteIfExists(fileFor(profile.name()));
    }

    /**
     * Sprawdza poprawność nazwy profilu.
     *
     * @return komunikat błędu albo {@code null}, gdy nazwa jest poprawna
     */
    public static String validateName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return "Podaj nazwę profilu.";
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            return "Nazwa może mieć najwyżej " + MAX_NAME_LENGTH + " znaków.";
        }
        for (char c : trimmed.toCharArray()) {
            if (FORBIDDEN_CHARS.indexOf(c) >= 0 || c < 32) {
                return "Nazwa nie może zawierać znaków  " + String.join(" ", FORBIDDEN_CHARS.split(""));
            }
        }
        if (trimmed.endsWith(".")) {
            return "Nazwa nie może kończyć się kropką.";
        }
        return null;
    }

    private Path fileFor(String name) {
        return directory.resolve(name.trim() + EXTENSION);
    }
}
