package com.photon.profiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.photon.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Zapis i odczyt profili jednego modułu jako plików JSON
 * (jeden plik na profil, nazwa pliku = nazwa profilu).
 * Format: {@code {"name": …, "updatedAt": …, "options": {…}}}.
 *
 * @param <O> typ ustawień modułu
 */
public class ProfileStore<O> {

    /** Znaki niedozwolone w nazwie profilu (bo nazwa jest też nazwą pliku). */
    public static final String FORBIDDEN_CHARS = "\\/:*?\"<>|";
    public static final int MAX_NAME_LENGTH = 60;

    private static final String EXTENSION = ".json";

    private final Path directory;
    private final Class<O> optionsType;

    public ProfileStore(Path directory, Class<O> optionsType) {
        this.directory = directory;
        this.optionsType = optionsType;
    }

    public Path getDirectory() {
        return directory;
    }

    /** Wczytuje wszystkie profile posortowane po nazwie. Uszkodzone pliki są pomijane. */
    public List<Profile<O>> loadAll() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Profile<O>> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(EXTENSION)).toList()) {
                try {
                    JsonNode node = Json.mapper().readTree(file.toFile());
                    String name = node.path("name").asText(null);
                    JsonNode options = node.get("options");
                    if (name == null || options == null || options.isNull()) {
                        continue;
                    }
                    Instant updatedAt = node.hasNonNull("updatedAt")
                            ? Json.mapper().treeToValue(node.get("updatedAt"), Instant.class) : null;
                    result.add(new Profile<>(name, updatedAt, Json.mapper().treeToValue(options, optionsType)));
                } catch (IOException | IllegalArgumentException e) {
                    System.err.println("Pominięto uszkodzony profil " + file + ": " + e.getMessage());
                }
            }
        }
        result.sort(Comparator.comparing(p -> p.name().toLowerCase(Locale.ROOT)));
        return result;
    }

    /** Zapisuje profil (nadpisuje istniejący o tej samej nazwie). Zapis jest atomowy. */
    public void save(Profile<O> profile) throws IOException {
        Json.writeAtomically(fileFor(profile.name()), profile);
    }

    /** Zmienia nazwę profilu: zapisuje go pod nową nazwą i usuwa stary plik. */
    public Profile<O> rename(Profile<O> profile, String newName) throws IOException {
        Profile<O> renamed = new Profile<>(newName, profile.updatedAt(), profile.options());
        save(renamed);
        if (!fileFor(newName).equals(fileFor(profile.name()))) {
            Files.deleteIfExists(fileFor(profile.name()));
        }
        return renamed;
    }

    public void delete(String name) throws IOException {
        Files.deleteIfExists(fileFor(name));
    }

    public void delete(Profile<O> profile) throws IOException {
        delete(profile.name());
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
