package com.photon.settings;

import com.photon.JsonFileStore;
import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;

public class AppSettingsStoreTest extends TestCase {

    private Path dir;
    private JsonFileStore<AppSettings> store;

    @Override
    protected void setUp() throws Exception {
        dir = Files.createTempDirectory("photon-settings-test");
        store = new JsonFileStore<>(dir.resolve("settings.json"), AppSettings.class, AppSettings.defaults());
    }

    @Override
    protected void tearDown() throws Exception {
        Files.deleteIfExists(dir.resolve("settings.json"));
        Files.deleteIfExists(dir);
    }

    public void testMissingFileGivesDefaults() {
        assertEquals(AppSettings.defaults(), store.load());
    }

    public void testRoundTrip() throws Exception {
        AppSettings custom = new AppSettings(StartupView.LAST_SECTION, false, true,
                Path.of("D:\\Cache PhotoN"), Path.of("E:\\Raporty"),
                false, true, false, 6, true, 2048, false, true, LogLevel.DEBUG);
        store.save(custom);
        assertEquals(custom, store.load());
    }

    public void testMissingFieldsTakeDefaults() throws Exception {
        // Plik ze starszej wersji aplikacji — zawiera tylko część pól.
        Files.writeString(dir.resolve("settings.json"), "{ \"confirmExit\": true, \"defaultThreads\": 3 }");
        AppSettings loaded = store.load();
        assertTrue(loaded.confirmExit());
        assertEquals(3, loaded.defaultThreads());
        // Pozostałe pola mają wartości domyślne, a nie false/0/null.
        assertTrue(loaded.rememberWindow());
        assertTrue(loaded.useRecycleBin());
        assertEquals(1024, loaded.cacheLimitMb());
        assertEquals(StartupView.WELCOME, loaded.startupView());
        assertEquals(LogLevel.INFO, loaded.logLevel());
    }

    public void testCorruptedFileGivesDefaults() throws Exception {
        Files.writeString(dir.resolve("settings.json"), "{ to nie jest json");
        assertEquals(AppSettings.defaults(), store.load());
    }

    public void testEffectiveThreads() {
        assertEquals(Runtime.getRuntime().availableProcessors(), AppSettings.defaults().effectiveThreads());
    }
}
