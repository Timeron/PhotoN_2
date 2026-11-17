package com.photon.duplicates.scan;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

/**
 * Plik znaleziony podczas skanowania.
 *
 * @param path          pełna ścieżka
 * @param size          rozmiar w bajtach
 * @param modified      data ostatniej modyfikacji
 * @param created       data utworzenia (gdy system jej nie zna — równa modyfikacji)
 * @param locationIndex indeks lokalizacji z konfiguracji (0 = najwyższy priorytet)
 */
public record ScannedFile(Path path, long size, Instant modified, Instant created, int locationIndex) {

    public String fileName() {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    /** Rozszerzenie małymi literami, bez kropki (pusty tekst, gdy brak). */
    public String extension() {
        return extensionOf(fileName());
    }

    /** Najwcześniejsza znana data pliku (utworzenia lub modyfikacji). */
    public Instant earliestDate() {
        return created.isBefore(modified) ? created : modified;
    }

    public static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
