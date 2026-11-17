package com.photon.duplicates;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Kompletna konfiguracja wyszukiwania duplikatów zebrana z formularza.
 *
 * @param locations           foldery do przeskanowania (kolejność = priorytet)
 * @param exclusions          foldery pomijane podczas skanowania
 * @param includeSubfolders   czy schodzić rekurencyjnie do podfolderów
 * @param skipHidden          czy pomijać ukryte/systemowe foldery i pliki
 * @param crossLocationOnly   czy raportować tylko duplikaty z różnych lokalizacji
 * @param extensions          rozszerzenia plików (małe litery, bez kropki)
 * @param method              sposób rozpoznawania duplikatów
 * @param similarityThreshold próg podobieństwa w % (tylko dla {@link SearchMethod#SIMILAR})
 * @param minSizeBytes        minimalny rozmiar pliku
 * @param maxSizeBytes        maksymalny rozmiar pliku, 0 = bez limitu
 * @param modifiedFrom        data modyfikacji od (null = bez ograniczenia)
 * @param modifiedTo          data modyfikacji do (null = bez ograniczenia)
 * @param keepStrategy        który plik uznać za oryginał
 * @param action              co zrobić z duplikatami
 * @param moveTarget          folder docelowy dla {@link DuplicateAction#MOVE_TO_FOLDER}
 * @param saveReport          czy zapisać raport CSV
 * @param threads             liczba wątków skanowania
 * @param useHashCache        czy zapamiętywać sumy kontrolne między skanowaniami
 * @param skipSymlinks        czy pomijać dowiązania symboliczne i skróty
 */
public record DuplicateSearchOptions(
        List<Path> locations,
        List<Path> exclusions,
        boolean includeSubfolders,
        boolean skipHidden,
        boolean crossLocationOnly,
        Set<String> extensions,
        SearchMethod method,
        int similarityThreshold,
        long minSizeBytes,
        long maxSizeBytes,
        LocalDate modifiedFrom,
        LocalDate modifiedTo,
        KeepStrategy keepStrategy,
        DuplicateAction action,
        Path moveTarget,
        boolean saveReport,
        int threads,
        boolean useHashCache,
        boolean skipSymlinks) {
}
