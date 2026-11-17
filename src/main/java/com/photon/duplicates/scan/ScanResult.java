package com.photon.duplicates.scan;

import com.photon.duplicates.DuplicateSearchOptions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Wynik skanowania.
 *
 * @param options      konfiguracja, z jaką wykonano skanowanie
 * @param groups       znalezione grupy duplikatów
 * @param scannedFiles liczba plików spełniających kryteria (przeanalizowanych)
 * @param scannedBytes łączny rozmiar przeanalizowanych plików
 * @param skippedFiles pliki pominięte, bo wybrana metoda ich nie obsługuje (np. filmy przy porównaniu obrazów)
 * @param errors       błędy odczytu (ograniczona liczba komunikatów)
 * @param errorCount   łączna liczba błędów
 * @param elapsed      czas skanowania
 * @param finishedAt   moment zakończenia
 */
public record ScanResult(
        DuplicateSearchOptions options,
        List<DuplicateGroup> groups,
        int scannedFiles,
        long scannedBytes,
        int skippedFiles,
        List<String> errors,
        int errorCount,
        Duration elapsed,
        Instant finishedAt) {

    public ScanResult {
        groups = List.copyOf(groups);
        errors = List.copyOf(errors);
    }

    public int duplicateCount() {
        return groups.stream().mapToInt(g -> g.count() - 1).sum();
    }

    public long reclaimableSize() {
        return groups.stream().mapToLong(DuplicateGroup::reclaimableSize).sum();
    }

    /** Ten sam wynik z inną listą grup (np. po przeniesieniu części plików). */
    public ScanResult withGroups(List<DuplicateGroup> newGroups) {
        return new ScanResult(options, newGroups, scannedFiles, scannedBytes, skippedFiles, errors, errorCount,
                elapsed, finishedAt);
    }
}
