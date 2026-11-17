package com.photon.duplicates;

import junit.framework.TestCase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public class DuplicateProfileStoreTest extends TestCase {

    private Path dir;
    private DuplicateProfileStore store;

    @Override
    protected void setUp() throws Exception {
        dir = Files.createTempDirectory("photon-profiles-test");
        store = new DuplicateProfileStore(dir);
    }

    @Override
    protected void tearDown() throws Exception {
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static DuplicateSearchOptions sampleOptions() {
        return new DuplicateSearchOptions(
                List.of(Path.of("C:\\Zdjęcia\\2024"), Path.of("D:\\Backup")),
                List.of(Path.of("C:\\Zdjęcia\\2024\\tmp")),
                true, true, false,
                new LinkedHashSet<>(List.of("jpg", "jpeg", "png", "mp4")),
                SearchMethod.SIMILAR, 85,
                10 * 1024L, 500L * 1024 * 1024,
                LocalDate.of(2020, 1, 1), null,
                KeepStrategy.LOCATION_PRIORITY, DuplicateAction.MOVE_TO_FOLDER,
                Path.of("E:\\Duplikaty"), true,
                8, true, true);
    }

    public void testSaveAndLoadRoundTripPreservesOptions() throws Exception {
        DuplicateProfile profile = new DuplicateProfile("Wakacje 2024", Instant.parse("2026-11-17T10:00:00Z"),
                sampleOptions());
        store.save(profile);

        List<DuplicateProfile> loaded = store.loadAll();
        assertEquals(1, loaded.size());
        assertEquals(profile, loaded.get(0));
        // Ścieżki zapisane czytelnie, nie jako URI.
        String json = Files.readString(dir.resolve("Wakacje 2024.json"));
        assertTrue(json, json.contains("C:\\\\Zdjęcia\\\\2024"));
    }

    public void testRenameMovesFile() throws Exception {
        DuplicateProfile profile = new DuplicateProfile("Stara", Instant.now(), sampleOptions());
        store.save(profile);
        DuplicateProfile renamed = store.rename(profile, "Nowa");

        assertEquals("Nowa", renamed.name());
        assertFalse(Files.exists(dir.resolve("Stara.json")));
        assertTrue(Files.exists(dir.resolve("Nowa.json")));
        assertEquals(List.of(renamed), store.loadAll());
    }

    public void testDeleteRemovesFile() throws Exception {
        DuplicateProfile profile = new DuplicateProfile("Do usunięcia", Instant.now(), sampleOptions());
        store.save(profile);
        store.delete(profile);
        assertTrue(store.loadAll().isEmpty());
    }

    public void testCorruptedFileIsSkipped() throws Exception {
        Files.writeString(dir.resolve("zepsuty.json"), "{ to nie jest json");
        store.save(new DuplicateProfile("Dobry", Instant.now(), sampleOptions()));
        List<DuplicateProfile> loaded = store.loadAll();
        assertEquals(1, loaded.size());
        assertEquals("Dobry", loaded.get(0).name());
    }

    public void testNameValidation() {
        assertNull(DuplicateProfileStore.validateName("Mój profil 1"));
        assertNotNull(DuplicateProfileStore.validateName("   "));
        assertNotNull(DuplicateProfileStore.validateName("a/b"));
        assertNotNull(DuplicateProfileStore.validateName("zły:znak"));
        assertNotNull(DuplicateProfileStore.validateName("kropka."));
        assertNotNull(DuplicateProfileStore.validateName("x".repeat(61)));
    }

    public void testSetEqualityIgnoresOrder() {
        // Formularz porównuje opcje przez equals — kolejność rozszerzeń nie może dawać „niezapisanych zmian”.
        DuplicateSearchOptions a = sampleOptions();
        Set<String> reordered = new LinkedHashSet<>(List.of("mp4", "png", "jpeg", "jpg"));
        DuplicateSearchOptions b = new DuplicateSearchOptions(a.locations(), a.exclusions(), a.includeSubfolders(),
                a.skipHidden(), a.crossLocationOnly(), reordered, a.method(), a.similarityThreshold(),
                a.minSizeBytes(), a.maxSizeBytes(), a.modifiedFrom(), a.modifiedTo(), a.keepStrategy(),
                a.action(), a.moveTarget(), a.saveReport(), a.threads(), a.useHashCache(), a.skipSymlinks());
        assertEquals(a, b);
    }
}
