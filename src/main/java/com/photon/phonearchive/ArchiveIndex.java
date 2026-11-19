package com.photon.phonearchive;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Spis plików archiwum (rozmiar → pliki) pamiętany między przebiegami archiwizacji w tle,
 * aby nie przechodzić przez całe archiwum co kilkanaście sekund.
 * <p>
 * Spis jest budowany od nowa, gdy:
 * <ul>
 *   <li>zmienił się zestaw folderów {@code RRRR-MM-DD},</li>
 *   <li>minęło {@code maxAge} od ostatniej budowy (zmiany w archiwum zrobione poza agentem).</li>
 * </ul>
 * Pliki przenoszone przez archiwizację są dopisywane na bieżąco. Pliki usunięte z archiwum
 * w międzyczasie nie szkodzą — przy porównywaniu nieczytelne pliki są pomijane.
 */
public class ArchiveIndex {

    private final Duration maxAge;
    private final Clock clock;
    private Map<Long, List<Path>> bySize;
    private List<Path> folders;
    private Instant builtAt;
    private int rebuilds;

    public ArchiveIndex(Duration maxAge, Clock clock) {
        this.maxAge = maxAge;
        this.clock = clock;
    }

    /** Aktualny spis — z pamięci albo zbudowany od nowa. Zwracana mapa jest uzupełniana przez archiwizację. */
    synchronized Map<Long, List<Path>> current(List<Path> currentFolders, Supplier<Map<Long, List<Path>>> builder) {
        Instant now = clock.instant();
        boolean stale = bySize == null
                || !currentFolders.equals(folders)
                || Duration.between(builtAt, now).compareTo(maxAge) >= 0;
        if (stale) {
            bySize = builder.get();
            folders = List.copyOf(currentFolders);
            builtAt = now;
            rebuilds++;
        }
        return bySize;
    }

    /** Wymusza zbudowanie spisu przy następnym przebiegu. */
    public synchronized void invalidate() {
        bySize = null;
    }

    /** Ile razy spis był budowany (do testów i diagnostyki). */
    public synchronized int rebuilds() {
        return rebuilds;
    }
}
