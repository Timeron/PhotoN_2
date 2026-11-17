package com.photon;

import java.nio.file.Path;

/**
 * Lokalizacje plików aplikacji na dysku użytkownika.
 */
public final class AppPaths {

    public static final String DATA_DIR_PROPERTY = "photon.dataDir";

    private static final String APP_DIR = "PhotoN2";

    private AppPaths() {
    }

    /**
     * Katalog danych aplikacji: {@code %APPDATA%\PhotoN2} na Windows,
     * {@code ~/.photon2} na pozostałych systemach.
     * Właściwość systemowa {@value #DATA_DIR_PROPERTY} nadpisuje tę lokalizację
     * (używana w testach, aby nie dotykać prawdziwych danych użytkownika).
     */
    public static Path dataDir() {
        String override = System.getProperty(DATA_DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, APP_DIR);
        }
        return Path.of(System.getProperty("user.home"), "." + APP_DIR.toLowerCase());
    }

    public static Path profilesDir(String module) {
        return dataDir().resolve("profiles").resolve(module);
    }

    public static Path settingsFile() {
        return dataDir().resolve("settings.json");
    }

    public static Path stateFile() {
        return dataDir().resolve("state.json");
    }

    public static Path logsDir() {
        return dataDir().resolve("logs");
    }

    /** Domyślny folder pamięci podręcznej (sumy kontrolne, miniatury). */
    public static Path defaultCacheDir() {
        return dataDir().resolve("cache");
    }

    /** Domyślny folder raportów: Dokumenty\PhotoN Raporty. */
    public static Path defaultReportsDir() {
        return Path.of(System.getProperty("user.home"), "Documents", "PhotoN Raporty");
    }
}
