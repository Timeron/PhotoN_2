package com.photon.phonearchive;

import com.photon.remote.Locations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Bezpieczne przenoszenie plików do archiwum: w razie błędu (np. zerwane połączenie z dyskiem sieciowym)
 * w archiwum nie zostaje niepełna kopia, a oryginał jest usuwany dopiero po udanym skopiowaniu.
 */
final class SafeTransfer {

    /** Rozszerzenie pliku tymczasowego podczas kopiowania (pomijane przy indeksowaniu archiwum). */
    static final String TMP_SUFFIX = ".photon-tmp";

    private SafeTransfer() {
    }

    /**
     * Przenosi (albo kopiuje) plik. W obrębie jednego dysku — zwykła zmiana nazwy; między dyskami (zwłaszcza
     * sieciowymi) zawsze kopia do pliku tymczasowego, sprawdzenie rozmiaru i dopiero wtedy usunięcie oryginału.
     *
     * @param keepOriginal kopiuj zamiast przenosić (pliki z chronionych folderów {@code __…})
     */
    static void transfer(Path file, Path destination, boolean keepOriginal) throws IOException {
        if (!keepOriginal && (Locations.sameServer(file, destination) || sameStore(file, destination.getParent()))) {
            try {
                Files.move(file, destination);
                return;
            } catch (IOException e) {
                if (Files.exists(destination) || !Files.exists(file)) {
                    throw e;
                }
                // Inny dysk lub inny problem — próbujemy bezpiecznie: kopia, weryfikacja, usunięcie.
            }
        }
        Path tmp = destination.resolveSibling(destination.getFileName() + TMP_SUFFIX);
        try {
            Files.copy(file, tmp, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(tmp) != Files.size(file)) {
                throw new IOException("kopia ma inny rozmiar niż oryginał");
            }
            Files.move(tmp, destination);
        } finally {
            Files.deleteIfExists(tmp);
        }
        if (!keepOriginal) {
            Files.delete(file);
        }
    }

    /**
     * Czy plik i folder leżą na tym samym dysku lokalnym. Folder może jeszcze nie istnieć (nowy folder z datą,
     * nowe archiwum) — liczy się dysk najbliższego istniejącego folderu nadrzędnego.
     */
    static boolean sameStore(Path file, Path folder) {
        if (Locations.isRemote(file) || Locations.isRemote(folder)) {
            return false; // różne połączenia albo serwer i dysk lokalny
        }
        try {
            Path existing = existingAncestor(folder.toAbsolutePath());
            return existing != null && Files.getFileStore(file).equals(Files.getFileStore(existing));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Sama ścieżka albo najbliższy istniejący folder nadrzędny (null, gdy nie istnieje nic). */
    static Path existingAncestor(Path path) {
        Path existing = path;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        return existing;
    }

    /** {@code IMG.jpg}, a gdy zajęte (inna zawartość): {@code IMG (1).jpg}, {@code IMG (2).jpg}… */
    static Path uniqueName(Path path) {
        if (!Files.exists(path)) {
            return path;
        }
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot <= 0 ? name : name.substring(0, dot);
        String ext = dot <= 0 ? "" : name.substring(dot);
        for (int i = 1; ; i++) {
            Path candidate = path.resolveSibling(base + " (" + i + ")" + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }
}
