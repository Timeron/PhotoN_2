package com.photon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pojedynczy obiekt zapisywany w pliku JSON.
 * <p>
 * Przy odczycie wartości z pliku są nakładane na wartości domyślne — pola dodane
 * w nowszej wersji aplikacji dostają więc rozsądne wartości zamiast zer/null.
 */
public class JsonFileStore<T> {

    private final Path file;
    private final Class<T> type;
    private final T defaults;

    public JsonFileStore(Path file, Class<T> type, T defaults) {
        this.file = file;
        this.type = type;
        this.defaults = defaults;
    }

    public Path getFile() {
        return file;
    }

    /** Wczytuje obiekt; przy braku lub uszkodzeniu pliku zwraca wartości domyślne. */
    public T load() {
        if (!Files.isRegularFile(file)) {
            return defaults;
        }
        try {
            ObjectNode merged = Json.mapper().valueToTree(defaults);
            JsonNode fromFile = Json.mapper().readTree(file.toFile());
            if (fromFile instanceof ObjectNode values) {
                merged.setAll(values);
            }
            return Json.mapper().treeToValue(merged, type);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Nie udało się wczytać " + file + ", używam wartości domyślnych: " + e.getMessage());
            return defaults;
        }
    }

    public void save(T value) throws IOException {
        Json.writeAtomically(file, value);
    }
}
