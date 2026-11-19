package com.photon.backup;

import com.photon.duplicates.scan.HashCache;
import com.photon.phonearchive.ArchiveIndex;
import com.photon.phonearchive.PhoneArchiveOptions;
import com.photon.phonearchive.PhoneArchiver;
import com.photon.phonearchive.SourceDuplicates;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.Set;
import java.util.TreeSet;

/**
 * Archiwizacja w tle według profilu „Archiwizacji telefonu”: każdy przebieg ({@link #runCycle}) przenosi
 * gotowe pliki do archiwum, sprawdza duplikaty i tworzy miniatury — tak samo jak zakładka „Archiwizacja telefonu”.
 * <p>
 * Różnice wynikające z pracy bez użytkownika:
 * <ul>
 *   <li>pliki, które mogą być jeszcze przesyłane, czekają na kolejny przebieg ({@link UploadGate}),</li>
 *   <li>duplikaty przy akcji „przenieś do folderu” są przenoszone od razu (to nic nie usuwa),</li>
 *   <li>duplikaty przy akcji „usuń” nie są usuwane automatycznie — trafiają na listę oczekujących
 *       i są usuwane dopiero po potwierdzeniu ({@link #removePending}).</li>
 * </ul>
 */
public class ArchiveWatcher {

    /**
     * Wynik jednego przebiegu.
     *
     * @param summary         podsumowanie archiwizacji (null, gdy przebieg się nie odbył — patrz {@code problem})
     * @param movedDuplicates duplikaty przeniesione do folderu z profilu (null, gdy profil ich nie przenosi)
     * @param newDuplicates   nowe duplikaty czekające na decyzję o usunięciu
     * @param problem         powód pominięcia przebiegu (np. niedostępny folder) albo null
     * @param changedFiles    pliki nowe lub zmienione od poprzedniego przebiegu (np. trwa przesyłanie)
     * @param stalled         pliki czekające bez żadnej zmiany od {@link #STALL_AFTER} (przesyłanie stoi)
     */
    public record CycleReport(Instant at, PhoneArchiver.Summary summary, PhoneArchiver.RemovalOutcome movedDuplicates,
                              List<PhoneArchiver.Duplicate> newDuplicates, String problem, int changedFiles,
                              List<Path> stalled) {

        public CycleReport {
            stalled = stalled == null ? List.of() : List.copyOf(stalled);
        }

        public int archived() {
            return summary == null ? 0 : summary.moved().size() + summary.copied().size();
        }

        public int waiting() {
            return summary == null ? 0 : summary.waiting();
        }

        public int thumbnails() {
            return summary == null ? 0 : summary.thumbnails().created();
        }

        public List<String> errors() {
            List<String> errors = new ArrayList<>();
            if (summary != null) {
                errors.addAll(summary.errors());
            }
            if (movedDuplicates != null) {
                errors.addAll(movedDuplicates.errors());
            }
            return errors;
        }

        /**
         * Czy pojawiło się coś nowego: zarchiwizowane pliki, nowe lub rosnące pliki (przesyłanie) albo nowe
         * duplikaty. Błędy, duplikaty zgłoszone wcześniej i pliki, które od dawna czekają bez zmian (np. puste),
         * się nie liczą — inaczej sprawdzanie nigdy by nie zwolniło.
         */
        public boolean activity() {
            return archived() > 0 || changedFiles > 0 || !newDuplicates.isEmpty()
                    || movedDuplicates != null && !movedDuplicates.removed().isEmpty();
        }

        /** Czy w przebiegu coś się wydarzyło (do dziennika). */
        public boolean eventful() {
            return problem != null || archived() > 0 || !newDuplicates.isEmpty() || !errors().isEmpty()
                    || movedDuplicates != null && !movedDuplicates.removed().isEmpty();
        }
    }

    private final String profileName;
    private final PhoneArchiveOptions options;
    private final HashCache cache;
    private final Clock clock;
    private final UploadGate gate;
    /** Spis archiwum pamiętany między przebiegami; pełne odświeżenie najpóźniej co {@link #INDEX_MAX_AGE}. */
    private final ArchiveIndex index;
    public static final Duration INDEX_MAX_AGE = Duration.ofMinutes(15);
    /** Po takim czasie bez zmian czekający plik jest zgłaszany jako „przesyłanie stoi”. */
    public static final Duration STALL_AFTER = Duration.ofMinutes(15);
    /** Duplikaty czekające na decyzję — osobna blokada, by okno nie czekało na trwający przebieg. */
    private final Map<Path, PhoneArchiver.Duplicate> pending = new LinkedHashMap<>();
    private final Object pendingLock = new Object();
    private volatile int pendingCount;
    /**
     * Czy archiwum miało już foldery z datą. Jeśli tak, a nagle nie widać żadnego, archiwum jest uznawane
     * za niedostępne (np. odłączony dysk sieciowy), a nie za puste — inaczej wszystko trafiłoby do niego ponownie.
     */
    private volatile boolean seenDateFolders;

    /**
     * @param cache       pamięć podręczna sum plików archiwum (null = bez pamięci)
     * @param quietPeriod jak długo plik musi się nie zmieniać, by uznać go za w pełni przesłany
     */
    public ArchiveWatcher(String profileName, PhoneArchiveOptions options, HashCache cache,
                          Duration quietPeriod, Clock clock) {
        this(profileName, options, cache, quietPeriod, clock, new ArchiveIndex(INDEX_MAX_AGE, clock));
    }

    /**
     * @param index spis archiwum — wspólny dla profili archiwizujących do tego samego archiwum (plik dodany
     *              przez jeden profil od razu jest duplikatem dla drugiego)
     */
    public ArchiveWatcher(String profileName, PhoneArchiveOptions options, HashCache cache,
                          Duration quietPeriod, Clock clock, ArchiveIndex index) {
        this.profileName = profileName;
        this.options = options;
        this.cache = cache;
        this.clock = clock;
        this.gate = new UploadGate(quietPeriod, clock);
        this.index = index;
    }

    /** Spis archiwum (do testów i diagnostyki). */
    public ArchiveIndex index() {
        return index;
    }

    public String profileName() {
        return profileName;
    }

    public PhoneArchiveOptions options() {
        return options;
    }

    /** Jeden przebieg archiwizacji. Bezpieczny do wywołania z dowolnego wątku (przebiegi nie nakładają się). */
    public synchronized CycleReport runCycle(BooleanSupplier cancelled) {
        Instant at = clock.instant();
        String problem = options.validate();
        if (problem != null) {
            return new CycleReport(at, null, null, List.of(), problem, 0, List.of());
        }
        gate.startCycle();
        PhoneArchiver.Summary summary = null;
        int changed;
        try {
            summary = new PhoneArchiver(options, LocalDate.ofInstant(at, clock.getZone()), cache)
                    .withGate(gate)
                    .withIndex(index)
                    .expectingDateFolders(seenDateFolders)
                    .run((message, done, total) -> { }, cancelled);
        } catch (PhoneArchiver.ArchiveUnavailableException e) {
            // Archiwum (np. dysk sieciowy) niedostępne — pliki zostają w źródle, spis zostanie zbudowany od nowa.
            index.invalidate();
            saveCache();
            return new CycleReport(at, null, null, List.of(),
                    e.getMessage() + (e instanceof PhoneArchiver.InsufficientSpaceException ? ""
                            : " Pliki zostały w folderze archiwizowanym."), 0, List.of());
        } finally {
            changed = gate.changesThisCycle();
            if (summary == null) {
                gate.endCycle(); // przebieg przerwany (wyjątek) — po udanym kończymy niżej, po policzeniu wstrzymanych
            }
        }
        // Przesyłanie stoi: plik czeka (niekompletny, pusty, zablokowany) i od dawna się nie zmienia.
        Set<Path> waitingFiles = new TreeSet<>(gate.waitedThisCycle());
        waitingFiles.addAll(summary.incomplete());
        List<Path> stalled = waitingFiles.stream()
                .filter(f -> gate.unchangedFor(f).compareTo(STALL_AFTER) >= 0).toList();
        gate.endCycle();
        if (summary.dateFolders() > 0) {
            seenDateFolders = true;
        }

        PhoneArchiver.RemovalOutcome moved = null;
        List<PhoneArchiver.Duplicate> fresh = new ArrayList<>();
        if (options.movesDuplicates()) {
            if (!summary.duplicates().isEmpty()) {
                moved = SourceDuplicates.move(summary.duplicates(), options.source(), options.duplicatesTarget());
            }
        } else {
            synchronized (pendingLock) {
                for (PhoneArchiver.Duplicate duplicate : summary.duplicates()) {
                    gate.ignore(duplicate.source()); // nie sprawdzamy go co minutę od nowa
                    if (pending.put(duplicate.source(), duplicate) == null) {
                        fresh.add(duplicate);
                    }
                }
            }
        }
        pendingDuplicates();
        saveCache();
        return new CycleReport(at, summary, moved, List.copyOf(fresh), null, changed, stalled);
    }

    private void saveCache() {
        if (cache != null) {
            try {
                cache.save();
            } catch (IOException e) {
                System.err.println("Nie udało się zapisać pamięci podręcznej: " + e.getMessage());
            }
        }
    }

    /** Duplikaty czekające na decyzję o usunięciu z folderu źródłowego. */
    public List<PhoneArchiver.Duplicate> pendingDuplicates() {
        synchronized (pendingLock) {
            pending.keySet().removeIf(path -> !Files.exists(path));
            pendingCount = pending.size();
            return List.copyOf(pending.values());
        }
    }

    /** Liczba oczekujących duplikatów z ostatniej aktualizacji (bez dostępu do dysku — dla okna). */
    public int pendingDuplicatesCountCached() {
        return pendingCount;
    }

    /** Usuwa oczekujące duplikaty z folderu źródłowego (kopie w archiwum zostają). */
    public PhoneArchiver.RemovalOutcome removePending(List<PhoneArchiver.Duplicate> duplicates, boolean toTrash) {
        PhoneArchiver.RemovalOutcome outcome = SourceDuplicates.remove(duplicates, toTrash);
        synchronized (pendingLock) {
            outcome.removed().forEach(path -> {
                pending.remove(path);
                gate.forget(path);
            });
        }
        pendingDuplicates();
        return outcome;
    }
}
