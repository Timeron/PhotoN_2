package com.photon.duplicates.scan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.photon.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;

/**
 * Pamięć podręczna odcisków plików między skanowaniami. Wpis jest ważny, dopóki
 * rozmiar i data modyfikacji pliku się nie zmienią.
 */
public class HashCache {

    /**
     * @param size          rozmiar pliku w chwili liczenia
     * @param modified      data modyfikacji (ms od epoki)
     * @param sha256        suma SHA-256 (null = nie liczono)
     * @param imageHash     hash percepcyjny (null = nie liczono)
     * @param metadataKey   klucz z metadanych (null = nie liczono, "" = brak metadanych)
     */
    public record Entry(long size, long modified, String sha256, Long imageHash, String metadataKey) {

        static Entry empty(ScannedFile file) {
            return new Entry(file.size(), file.modified().toEpochMilli(), null, null, null);
        }

        boolean matches(ScannedFile file) {
            return size == file.size() && modified == file.modified().toEpochMilli();
        }

        Entry withSha256(String value) {
            return new Entry(size, modified, value, imageHash, metadataKey);
        }

        Entry withImageHash(Long value) {
            return new Entry(size, modified, sha256, value, metadataKey);
        }

        Entry withMetadataKey(String value) {
            return new Entry(size, modified, sha256, imageHash, value);
        }
    }

    private static final TypeReference<Map<String, Entry>> TYPE = new TypeReference<>() {
    };

    private final Path file;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile boolean changed;

    public HashCache(Path file) {
        this.file = file;
    }

    /** Domyślny plik pamięci podręcznej w folderze cache aplikacji. */
    public static HashCache inCacheDir(Path cacheDir) {
        return new HashCache(cacheDir.resolve("hashes").resolve("fingerprints.json"));
    }

    /** Wczytuje plik; uszkodzony lub brakujący plik oznacza pustą pamięć. */
    public HashCache load() {
        if (Files.isRegularFile(file)) {
            try {
                entries.putAll(Json.mapper().readValue(file.toFile(), TYPE));
            } catch (IOException e) {
                System.err.println("Pominięto uszkodzoną pamięć podręczną " + file + ": " + e.getMessage());
            }
        }
        return this;
    }

    public void save() throws IOException {
        if (changed) {
            Json.writeAtomically(file, entries);
            changed = false;
        }
    }

    public int size() {
        return entries.size();
    }

    /** Suma SHA-256 pliku — z pamięci, gdy plik się nie zmienił, inaczej liczona i zapamiętywana. */
    public String sha256(Path path) throws IOException {
        BasicFileAttributes attrs =
                Files.readAttributes(path, BasicFileAttributes.class);
        Instant modified = attrs.lastModifiedTime().toInstant();
        ScannedFile scanned = new ScannedFile(path, attrs.size(), modified, modified, 0);
        Entry entry = get(scanned);
        if (entry.sha256() == null) {
            entry = entry.withSha256(Fingerprints.sha256(path));
            put(scanned, entry);
        }
        return entry.sha256();
    }

    /** Aktualny wpis dla pliku (pusty, gdy brak lub plik się zmienił). */
    Entry get(ScannedFile scanned) {
        Entry entry = entries.get(key(scanned));
        return entry != null && entry.matches(scanned) ? entry : Entry.empty(scanned);
    }

    void put(ScannedFile scanned, Entry entry) {
        entries.put(key(scanned), entry);
        changed = true;
    }

    private static String key(ScannedFile scanned) {
        return scanned.path().toAbsolutePath().toString();
    }
}
