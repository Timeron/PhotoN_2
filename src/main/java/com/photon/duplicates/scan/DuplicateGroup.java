package com.photon.duplicates.scan;

import com.photon.duplicates.MediaFormat;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Grupa plików uznanych za duplikaty. Pierwszy plik to oryginał (wg strategii z konfiguracji),
 * pozostałe to kopie.
 */
public record DuplicateGroup(List<ScannedFile> files) {

    public DuplicateGroup {
        if (files.size() < 2) {
            throw new IllegalArgumentException("Grupa duplikatów musi mieć co najmniej 2 pliki");
        }
        files = List.copyOf(files);
    }

    public ScannedFile original() {
        return files.get(0);
    }

    /** Kopie bez oryginału. */
    public List<ScannedFile> duplicates() {
        return files.subList(1, files.size());
    }

    /** Liczba wszystkich plików w grupie (oryginał + kopie). */
    public int count() {
        return files.size();
    }

    /** Nazwa oryginału. */
    public String name() {
        return original().fileName();
    }

    /** Rozszerzenie oryginału. */
    public String extension() {
        return original().extension();
    }

    /** Rodzaj pliku (zdjęcie / film), gdy rozszerzenie jest znane. */
    public Optional<MediaFormat.Kind> kind() {
        return MediaFormat.forExtension(extension()).map(MediaFormat::getKind);
    }

    /** Rozmiar pojedynczego pliku (oryginału). */
    public long fileSize() {
        return original().size();
    }

    /** Suma rozmiarów wszystkich powtórzeń pliku. */
    public long totalSize() {
        return files.stream().mapToLong(ScannedFile::size).sum();
    }

    /** Miejsce do odzyskania po usunięciu kopii. */
    public long reclaimableSize() {
        return totalSize() - fileSize();
    }

    /** Grupa bez podanych plików albo pusta, gdy zostałby mniej niż 2 pliki. */
    public Optional<DuplicateGroup> without(Set<Path> removed) {
        List<ScannedFile> remaining = files.stream().filter(f -> !removed.contains(f.path())).toList();
        return remaining.size() < 2 ? Optional.empty() : Optional.of(new DuplicateGroup(remaining));
    }
}
