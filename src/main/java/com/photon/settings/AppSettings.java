package com.photon.settings;

import com.photon.AppPaths;

import java.nio.file.Path;

/**
 * Ustawienia aplikacji edytowane w sekcji „Ustawienia”.
 *
 * @param startupView           co pokazać po uruchomieniu
 * @param rememberWindow        czy przywracać rozmiar i położenie okna
 * @param confirmExit           czy pytać przed zamknięciem aplikacji
 * @param cacheDir              folder pamięci podręcznej (null = domyślny)
 * @param reportsDir            folder raportów (null = domyślny)
 * @param confirmFileOperations czy zawsze pytać przed usunięciem/przeniesieniem plików
 * @param useRecycleBin         czy usuwać do kosza zamiast trwale
 * @param keepOperationLog      czy prowadzić dziennik operacji na plikach
 * @param defaultThreads        domyślna liczba wątków skanowania (0 = automatycznie)
 * @param lowPriorityScan       czy obniżać priorytet skanowania
 * @param cacheLimitMb          limit rozmiaru pamięci podręcznej w MB
 * @param notifyOnFinish        powiadomienie systemowe po zakończeniu długich operacji
 * @param soundOnFinish         dźwięk po zakończeniu skanowania
 * @param logLevel              szczegółowość dziennika zdarzeń
 */
public record AppSettings(
        StartupView startupView,
        boolean rememberWindow,
        boolean confirmExit,
        Path cacheDir,
        Path reportsDir,
        boolean confirmFileOperations,
        boolean useRecycleBin,
        boolean keepOperationLog,
        int defaultThreads,
        boolean lowPriorityScan,
        int cacheLimitMb,
        boolean notifyOnFinish,
        boolean soundOnFinish,
        LogLevel logLevel) {

    public static AppSettings defaults() {
        return new AppSettings(
                StartupView.WELCOME, true, false,
                null, null,
                true, true, true,
                0, false, 1024,
                true, false,
                LogLevel.INFO);
    }

    public Path effectiveCacheDir() {
        return cacheDir != null ? cacheDir : AppPaths.defaultCacheDir();
    }

    public Path effectiveReportsDir() {
        return reportsDir != null ? reportsDir : AppPaths.defaultReportsDir();
    }

    /** Liczba wątków do użycia — dla „automatycznie” równa liczbie rdzeni procesora. */
    public int effectiveThreads() {
        return defaultThreads > 0 ? defaultThreads : Runtime.getRuntime().availableProcessors();
    }
}
