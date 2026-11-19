package com.photon.phonearchive;

import com.photon.thumbnails.ThumbnailMakerTest;
import com.photon.thumbnails.ThumbnailOptions;
import com.photon.thumbnails.XmpHash;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhoneArchiverTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 18);

    @TempDir
    Path dir;

    @Test
    void recognisesDateFolderNames() {
        assertTrue(PhoneArchiver.isDateFolderName("2016-02-23"));
        assertTrue(PhoneArchiver.isDateFolderName("2024-02-29"));
        assertFalse(PhoneArchiver.isDateFolderName("2016-2-23"));
        assertFalse(PhoneArchiver.isDateFolderName("2016-02-30"));
        assertFalse(PhoneArchiver.isDateFolderName("2023-02-29"));
        assertFalse(PhoneArchiver.isDateFolderName("2016-02-23 wakacje"));
        assertFalse(PhoneArchiver.isDateFolderName("Zdjęcia"));
    }

    @Test
    void listsOnlyDateFoldersOfArchive() throws IOException {
        Path archive = dir.resolve("Archiwum");
        Files.createDirectories(archive.resolve("2016-02-23"));
        Files.createDirectories(archive.resolve("2020-01-05"));
        Files.createDirectories(archive.resolve("Inne"));
        Files.writeString(archive.resolve("2021-01-01"), "plik, nie folder");
        assertEquals(List.of(archive.resolve("2016-02-23"), archive.resolve("2020-01-05")),
                PhoneArchiver.dateFolders(archive));
        assertTrue(PhoneArchiver.dateFolders(dir.resolve("brak")).isEmpty());
    }

    @Test
    void movesNewMediaAndKeepsDuplicates() throws IOException {
        Path phone = dir.resolve("Telefon");
        Path archive = dir.resolve("Archiwum");
        write(phone.resolve("IMG_1.jpg"), "zdjęcie 1");
        write(phone.resolve("Camera/VID_2.mp4"), "film 2");
        write(phone.resolve("IMG_3.jpg"), "zdjęcie 3 — już w archiwum");
        write(phone.resolve("notatka.txt"), "nie jest zdjęciem");
        write(archive.resolve("2016-02-23/inna-nazwa.jpg"), "zdjęcie 3 — już w archiwum");
        // Ta sama zawartość poza folderem z datą nie liczy się jako duplikat.
        write(archive.resolve("Inne/IMG_1.jpg"), "zdjęcie 1");
        FileTime time = FileTime.fromMillis(1_500_000_000_000L);
        Files.setLastModifiedTime(phone.resolve("IMG_1.jpg"), time);

        PhoneArchiver.Summary summary = run(phone, archive, true);

        Path day = archive.resolve("2026-11-18");
        assertEquals(day, summary.dayFolder());
        assertEquals(2, summary.moved().size());
        assertEquals("zdjęcie 1", Files.readString(day.resolve("IMG_1.jpg")));
        assertEquals("film 2", Files.readString(day.resolve("VID_2.mp4")));
        assertEquals(time, Files.getLastModifiedTime(day.resolve("IMG_1.jpg")), "data pliku zachowana");
        assertFalse(Files.exists(phone.resolve("IMG_1.jpg")), "przeniesiony, nie skopiowany");
        assertFalse(Files.exists(day.resolve("notatka.txt")));
        assertTrue(Files.exists(phone.resolve("notatka.txt")));

        assertEquals(1, summary.duplicates().size());
        PhoneArchiver.Duplicate duplicate = summary.duplicates().get(0);
        assertEquals(phone.resolve("IMG_3.jpg"), duplicate.source());
        assertEquals(archive.resolve("2016-02-23/inna-nazwa.jpg"), duplicate.existing());
        assertTrue(Files.exists(phone.resolve("IMG_3.jpg")), "duplikat zostaje do decyzji użytkownika");
        assertEquals(1, summary.dateFolders());
        assertTrue(summary.errors().isEmpty(), summary.errors().toString());
    }

    @Test
    void withoutSubfoldersOnlyTopLevelIsArchived() throws IOException {
        Path phone = dir.resolve("Telefon");
        Path archive = dir.resolve("Archiwum");
        write(phone.resolve("IMG_1.jpg"), "1");
        write(phone.resolve("Camera/IMG_2.jpg"), "2");

        PhoneArchiver.Summary summary = run(phone, archive, false);

        assertEquals(1, summary.moved().size());
        assertTrue(Files.exists(phone.resolve("Camera/IMG_2.jpg")));
    }

    @Test
    void identicalFilesInSourceAreArchivedOnceAndNamesDoNotClash() throws IOException {
        Path phone = dir.resolve("Telefon");
        Path archive = dir.resolve("Archiwum");
        write(phone.resolve("A/IMG_1.jpg"), "to samo");
        write(phone.resolve("B/IMG_1.jpg"), "to samo");
        write(phone.resolve("C/IMG_1.jpg"), "inna zawartość");
        write(archive.resolve("2026-11-18/IMG_1.jpg"), "jeszcze inna zawartość, ten sam rozmiar?");

        PhoneArchiver.Summary summary = run(phone, archive, true);

        Path day = archive.resolve("2026-11-18");
        assertEquals(2, summary.moved().size());
        assertEquals(1, summary.duplicates().size());
        assertEquals(phone.resolve("B/IMG_1.jpg"), summary.duplicates().get(0).source());
        assertEquals("jeszcze inna zawartość, ten sam rozmiar?", Files.readString(day.resolve("IMG_1.jpg")),
                "istniejący plik archiwum nie jest nadpisywany");
        assertEquals("to samo", Files.readString(day.resolve("IMG_1 (1).jpg")));
        assertEquals("inna zawartość", Files.readString(day.resolve("IMG_1 (2).jpg")));
    }

    @Test
    void removesDuplicatesFromSourceButKeepsArchiveCopy() throws IOException {
        Path phone = dir.resolve("Telefon");
        Path archive = dir.resolve("Archiwum");
        write(phone.resolve("IMG_1.jpg"), "zdjęcie");
        write(archive.resolve("2020-05-01/IMG_1.jpg"), "zdjęcie");

        PhoneArchiver.Summary summary = run(phone, archive, true);
        assertEquals(1, summary.duplicates().size());
        assertTrue(summary.moved().isEmpty());

        PhoneArchiver.RemovalOutcome outcome = PhoneArchiver.removeFromSource(summary.duplicates(), false);
        assertEquals(List.of(phone.resolve("IMG_1.jpg")), outcome.removed());
        assertFalse(Files.exists(phone.resolve("IMG_1.jpg")));
        assertTrue(Files.exists(archive.resolve("2020-05-01/IMG_1.jpg")));
    }

    @Test
    void doesNotRemoveSourceWhenArchiveCopyDisappeared() throws IOException {
        Path phone = dir.resolve("Telefon");
        Path archive = dir.resolve("Archiwum");
        write(phone.resolve("IMG_1.jpg"), "zdjęcie");
        write(archive.resolve("2020-05-01/IMG_1.jpg"), "zdjęcie");
        PhoneArchiver.Summary summary = run(phone, archive, true);

        Files.delete(archive.resolve("2020-05-01/IMG_1.jpg"));
        PhoneArchiver.RemovalOutcome outcome = PhoneArchiver.removeFromSource(summary.duplicates(), false);

        assertTrue(outcome.removed().isEmpty());
        assertEquals(1, outcome.errors().size());
        assertTrue(Files.exists(phone.resolve("IMG_1.jpg")));
    }

    @Test
    void protectedSourceFilesAreCopiedAndNeverRemoved() throws IOException {
        Path phone = dir.resolve("__Telefon");
        Path archive = dir.resolve("Archiwum");
        write(phone.resolve("IMG_1.jpg"), "nowe");
        write(phone.resolve("IMG_2.jpg"), "już jest");
        write(archive.resolve("2020-05-01/IMG_2.jpg"), "już jest");

        PhoneArchiver.Summary summary = run(phone, archive, true);

        assertTrue(summary.moved().isEmpty());
        assertEquals(1, summary.copied().size());
        assertTrue(Files.exists(phone.resolve("IMG_1.jpg")), "chroniony oryginał zostaje");
        assertTrue(Files.exists(archive.resolve("2026-11-18/IMG_1.jpg")));

        PhoneArchiver.RemovalOutcome outcome = PhoneArchiver.removeFromSource(summary.duplicates(), false);
        assertEquals(1, outcome.skippedProtected());
        assertTrue(Files.exists(phone.resolve("IMG_2.jpg")));
    }

    @Test
    void validatesFolders() throws IOException {
        Path archive = dir.resolve("Archiwum");
        Path inside = archive.resolve("2020-01-01");
        Files.createDirectories(inside);
        assertNotNull(new PhoneArchiveOptions(inside, archive, true).validate());
        assertNotNull(new PhoneArchiveOptions(archive, archive, true).validate());
        assertNotNull(new PhoneArchiveOptions(null, archive, true).validate());
        assertNotNull(new PhoneArchiveOptions(archive, null, true).validate());
        Path phone = Files.createDirectories(dir.resolve("Telefon"));
        assertNull(new PhoneArchiveOptions(phone, archive, true).validate());
        assertNull(new PhoneArchiveOptions(phone, dir.resolve("nowe-archiwum"), true).validate(),
                "archiwum zostanie utworzone");
    }

    @Test
    void makesThumbnailsOfArchivedPhotosWithMetadata() throws IOException {
        Path phone = dir.resolve("Telefon");
        Path archive = dir.resolve("Archiwum");
        Path thumbs = dir.resolve("Miniatury");
        ThumbnailMakerTest.writePhoto(phone.resolve("IMG_1.jpg"), 2000, 1500, true);
        ThumbnailMakerTest.writePhoto(phone.resolve("IMG_2.jpg"), 600, 400, true);
        write(phone.resolve("Screenshot.png"), "png");
        write(phone.resolve("VID_1.mp4"), "film");
        // Duplikat — już w archiwum, więc bez miniatury.
        ThumbnailMakerTest.writePhoto(phone.resolve("IMG_3.jpg"), 1000, 800, false);
        Files.createDirectories(archive.resolve("2020-01-01"));
        Files.copy(phone.resolve("IMG_3.jpg"), archive.resolve("2020-01-01/IMG_3.jpg"));
        String originalHash = ThumbnailMakerTest.sha256(phone.resolve("IMG_1.jpg"));

        PhoneArchiver.Summary summary = new PhoneArchiver(
                new PhoneArchiveOptions(phone, archive, true, true, 800, thumbs), TODAY, null)
                .run((message, done, total) -> { }, () -> false);

        Path day = thumbs.resolve("2026-11-18");
        assertTrue(summary.errors().isEmpty(), summary.errors().toString());
        assertEquals(4, summary.moved().size());
        assertEquals(new PhoneArchiver.ThumbnailStats(2, 0, 1, day), summary.thumbnails());
        try (var files = Files.list(day)) {
            assertEquals(List.of(day.resolve("IMG_1.jpg"), day.resolve("IMG_2.jpg")), files.sorted().toList());
        }

        BufferedImage thumb = ImageIO.read(day.resolve("IMG_1.jpg").toFile());
        assertEquals(800, thumb.getWidth());
        assertEquals(600, thumb.getHeight());
        assertEquals(400, ImageIO.read(day.resolve("IMG_2.jpg").toFile()).getHeight(), "mniejsze nie powiększane");

        // Metadane jak w „Miniaturyzacji”: EXIF i XMP oryginału + suma SHA-256 oryginału w XMP.
        String bytes = new String(Files.readAllBytes(day.resolve("IMG_1.jpg")), StandardCharsets.ISO_8859_1);
        assertTrue(bytes.contains("PhotoN Test"), "EXIF Make zachowany");
        assertTrue(bytes.contains("xmp:Rating"), "XMP zachowany");
        assertTrue(bytes.contains(XmpHash.PREFIX + ":" + XmpHash.PROPERTY), "hash w XMP");
        assertTrue(bytes.contains(originalHash), "hash oryginału");
        assertEquals(originalHash, ThumbnailMakerTest.sha256(archive.resolve("2026-11-18/IMG_1.jpg")),
                "oryginał w archiwum nienaruszony");
    }

    @Test
    void thumbnailsDisabledByDefault() throws IOException {
        Path phone = dir.resolve("Telefon");
        ThumbnailMakerTest.writePhoto(phone.resolve("IMG_1.jpg"), 400, 300, false);
        PhoneArchiver.Summary summary = run(phone, dir.resolve("Archiwum"), true);
        assertEquals(PhoneArchiver.ThumbnailStats.NONE, summary.thumbnails());
    }

    @Test
    void validatesThumbnailFolder() throws IOException {
        Path phone = Files.createDirectories(dir.resolve("Telefon"));
        Path archive = dir.resolve("Archiwum");
        assertNotNull(new PhoneArchiveOptions(phone, archive, true, true, 800, null).validate());
        assertNotNull(new PhoneArchiveOptions(phone, archive, true, true, 800, archive).validate(),
                "miniatury zastąpiłyby oryginały");
        assertNotNull(new PhoneArchiveOptions(phone, archive, true, true, 800, phone.resolve("mini")).validate());
        assertNotNull(new PhoneArchiveOptions(phone, archive, true, true, 800,
                archive.resolve("2020-01-01/mini")).validate());
        assertNotNull(new PhoneArchiveOptions(phone, archive, true, true, 5, dir.resolve("Mini")).validate());
        assertNull(new PhoneArchiveOptions(phone, archive, true, true, 800, archive.resolve("Miniatury")).validate());
        assertNull(new PhoneArchiveOptions(phone, archive, true, true, 800, dir.resolve("Mini")).validate());
        assertNull(new PhoneArchiveOptions(phone, archive, true, false, 800, null).validate(),
                "wyłączone miniatury nie wymagają folderu");
    }

    @Test
    void oldProfilesWithoutThumbnailFieldsLoad() throws IOException {
        Path profiles = Files.createDirectories(dir.resolve("profiles"));
        Files.writeString(profiles.resolve("stary.json"), """
                {"name":"Stary","updatedAt":"2026-11-01T10:00:00Z",
                 "options":{"source":"C:/Telefon","target":"D:/Archiwum","includeSubfolders":true}}
                """);
        List<com.photon.profiles.Profile<PhoneArchiveOptions>> loaded =
                new PhoneArchiveProfileStore(profiles).loadAll();
        assertEquals(1, loaded.size());
        PhoneArchiveOptions options = loaded.get(0).options();
        assertFalse(options.makeThumbnails());
        assertEquals(ThumbnailOptions.DEFAULT_SIDE, options.thumbnailSide());
        assertNull(options.thumbnailTarget());
        assertEquals(PhoneDuplicateAction.REMOVE, options.duplicateAction());
        assertNull(options.duplicatesTarget());
    }

    @Test
    void movesDuplicatesKeepingFolderStructure() throws IOException {
        Path phone = dir.resolve("Telefon");
        Path archive = dir.resolve("Archiwum");
        Path dups = dir.resolve("Duplikaty");
        write(phone.resolve("Camera/IMG_1.jpg"), "jeden");
        write(phone.resolve("IMG_2.jpg"), "dwa");
        write(archive.resolve("2020-01-01/IMG_1.jpg"), "jeden");
        write(archive.resolve("2020-01-02/x.jpg"), "dwa");
        write(dups.resolve("IMG_2.jpg"), "inny plik o tej nazwie");

        PhoneArchiver.Summary summary = run(phone, archive, true);
        assertEquals(2, summary.duplicates().size());

        PhoneArchiver.RemovalOutcome outcome = PhoneArchiver.moveDuplicates(summary.duplicates(), phone, dups);

        assertTrue(outcome.errors().isEmpty(), outcome.errors().toString());
        assertEquals(2, outcome.removed().size());
        assertEquals("jeden", Files.readString(dups.resolve("Camera/IMG_1.jpg")));
        assertEquals("dwa", Files.readString(dups.resolve("IMG_2 (1).jpg")), "istniejący plik nie jest nadpisywany");
        assertEquals("inny plik o tej nazwie", Files.readString(dups.resolve("IMG_2.jpg")));
        assertFalse(Files.exists(phone.resolve("Camera/IMG_1.jpg")));
        assertTrue(Files.exists(archive.resolve("2020-01-01/IMG_1.jpg")));
    }

    @Test
    void protectedDuplicatesAreNotMoved() throws IOException {
        Path phone = dir.resolve("__Telefon");
        Path archive = dir.resolve("Archiwum");
        write(phone.resolve("IMG_1.jpg"), "jeden");
        write(archive.resolve("2020-01-01/IMG_1.jpg"), "jeden");
        PhoneArchiver.Summary summary = run(phone, archive, true);

        PhoneArchiver.RemovalOutcome outcome = PhoneArchiver.moveDuplicates(summary.duplicates(), phone,
                dir.resolve("Duplikaty"));

        assertEquals(1, outcome.skippedProtected());
        assertTrue(Files.exists(phone.resolve("IMG_1.jpg")));
    }

    @Test
    void validatesDuplicatesFolder() throws IOException {
        Path phone = Files.createDirectories(dir.resolve("Telefon"));
        Path archive = dir.resolve("Archiwum");
        PhoneDuplicateAction move = PhoneDuplicateAction.MOVE_TO_FOLDER;
        assertNotNull(new PhoneArchiveOptions(phone, archive, true, false, 800, null, move, null).validate());
        assertNotNull(new PhoneArchiveOptions(phone, archive, true, false, 800, null, move,
                phone.resolve("dup")).validate(), "w folderze archiwizowanym");
        assertNotNull(new PhoneArchiveOptions(phone, archive, true, false, 800, null, move, archive).validate());
        assertNotNull(new PhoneArchiveOptions(phone, archive, true, false, 800, null, move,
                archive.resolve("2020-01-01")).validate());
        assertNull(new PhoneArchiveOptions(phone, archive, true, false, 800, null, move,
                archive.resolve("Duplikaty")).validate());
        assertNull(new PhoneArchiveOptions(phone, archive, true, false, 800, null, move,
                dir.resolve("Duplikaty")).validate());
        assertNull(new PhoneArchiveOptions(phone, archive, true, false, 800, null,
                PhoneDuplicateAction.REMOVE, null).validate(), "usuwanie nie wymaga folderu");
    }

    @Test
    void archiveIndexIsReusedAndSkippedWhenSourceIsEmpty() throws IOException {
        Path phone = Files.createDirectories(dir.resolve("Telefon"));
        Path archive = dir.resolve("Archiwum");
        write(archive.resolve("2020-01-01/stare.jpg"), "stare");
        ArchiveIndex index = new ArchiveIndex(java.time.Duration.ofMinutes(15), java.time.Clock.systemUTC());
        PhoneArchiveOptions options = new PhoneArchiveOptions(phone, archive, true);

        // Pusty folder źródłowy — archiwum nie jest czytane.
        new PhoneArchiver(options, TODAY, null).withIndex(index).run((m, d, t) -> { }, () -> false);
        assertEquals(0, index.rebuilds());

        write(phone.resolve("IMG_1.jpg"), "jeden");
        new PhoneArchiver(options, TODAY, null).withIndex(index).run((m, d, t) -> { }, () -> false);
        assertEquals(1, index.rebuilds());

        // Nowy folder z datą (2026-11-18) — spis budowany od nowa; potem używany ponownie.
        write(phone.resolve("IMG_2.jpg"), "dwa");
        new PhoneArchiver(options, TODAY, null).withIndex(index).run((m, d, t) -> { }, () -> false);
        assertEquals(2, index.rebuilds());

        // Plik przeniesiony przez archiwizację jest w spisie — jego kopia to duplikat, bez czytania archiwum.
        write(phone.resolve("IMG_2 kopia.jpg"), "dwa");
        PhoneArchiver.Summary summary = new PhoneArchiver(options, TODAY, null).withIndex(index)
                .run((m, d, t) -> { }, () -> false);
        assertEquals(2, index.rebuilds(), "spis z pamięci");
        assertEquals(1, summary.duplicates().size());
        assertEquals(archive.resolve("2026-11-18/IMG_2.jpg"), summary.duplicates().get(0).existing());
    }

    @Test
    void unreachableArchiveStopsBeforeMovingAnything() throws IOException {
        Path phone = dir.resolve("Telefon");
        write(phone.resolve("IMG_1.jpg"), "1");
        // Jak odłączony dysk sieciowy: nie ma ani archiwum, ani folderu nad nim.
        Path archive = dir.resolve("OdlaczonyDysk").resolve("Archiwum");

        PhoneArchiver.ArchiveUnavailableException error = assertThrows(PhoneArchiver.ArchiveUnavailableException.class,
                () -> run(phone, archive, true));
        assertTrue(error.getMessage().contains("niedostępny"), error.getMessage());
        assertTrue(Files.exists(phone.resolve("IMG_1.jpg")), "plik zostaje w źródle");
        assertFalse(Files.exists(dir.resolve("OdlaczonyDysk")), "archiwum nie jest tworzone w innym miejscu");
    }

    @Test
    void newArchiveIsCreatedWhenItsParentExists() throws IOException {
        Path phone = dir.resolve("Telefon");
        write(phone.resolve("IMG_1.jpg"), "1");
        PhoneArchiver.Summary summary = run(phone, dir.resolve("NoweArchiwum"), true);
        assertEquals(1, summary.moved().size());
        assertTrue(Files.exists(dir.resolve("NoweArchiwum/2026-11-18/IMG_1.jpg")));
    }

    @Test
    void archiveThatHadDateFoldersButShowsNoneIsTreatedAsUnavailable() throws IOException {
        Path phone = dir.resolve("Telefon");
        Path archive = dir.resolve("Archiwum");
        write(phone.resolve("IMG_1.jpg"), "1");
        Files.createDirectories(archive); // np. pusty punkt montowania odłączonego dysku
        PhoneArchiveOptions options = new PhoneArchiveOptions(phone, archive, true);

        assertThrows(PhoneArchiver.ArchiveUnavailableException.class, () -> new PhoneArchiver(options, TODAY, null)
                .expectingDateFolders(true).run((m, d, t) -> { }, () -> false));
        assertTrue(Files.exists(phone.resolve("IMG_1.jpg")));
        assertFalse(Files.exists(archive.resolve("2026-11-18")));

        Files.delete(archive);
        assertThrows(PhoneArchiver.ArchiveUnavailableException.class, () -> new PhoneArchiver(options, TODAY, null)
                .expectingDateFolders(true).run((m, d, t) -> { }, () -> false), "archiwum, które istniało, zniknęło");
        assertFalse(Files.exists(archive), "nie jest odtwarzane");
    }

    @Test
    void archiveDisappearingMidRunStopsTheRun() throws IOException {
        Path phone = dir.resolve("Telefon");
        Path archive = dir.resolve("Archiwum");
        Files.createDirectories(archive.resolve("2020-01-01"));
        write(phone.resolve("IMG_1.jpg"), "1");
        write(phone.resolve("IMG_2.jpg"), "2");
        write(phone.resolve("IMG_3.jpg"), "3");
        Path disconnected = dir.resolve("Archiwum-odlaczone");
        int[] checked = {0};
        PhoneArchiver archiver = new PhoneArchiver(new PhoneArchiveOptions(phone, archive, true), TODAY, null)
                .withGate(file -> {
                    if (++checked[0] == 2) {
                        Files.move(archive, disconnected); // połączenie zerwane po pierwszym pliku
                    }
                    return PhoneArchiver.FileState.READY;
                });

        PhoneArchiver.ArchiveUnavailableException error = assertThrows(PhoneArchiver.ArchiveUnavailableException.class,
                () -> archiver.run((m, d, t) -> { }, () -> false));
        assertTrue(error.getMessage().contains("w trakcie"), error.getMessage());
        assertEquals(2, checked[0], "kolejne pliki nie są już próbowane");
        assertTrue(Files.exists(disconnected.resolve("2026-11-18/IMG_1.jpg")), "pierwszy plik bezpiecznie w archiwum");
        assertTrue(Files.exists(phone.resolve("IMG_2.jpg")));
        assertTrue(Files.exists(phone.resolve("IMG_3.jpg")));
        assertFalse(Files.exists(archive), "archiwum nie zostało odtworzone");
    }

    @Test
    void truncatedFilesStayInSource() throws Exception {
        Path phone = dir.resolve("Telefon");
        Path archive = dir.resolve("Archiwum");
        Path full = dir.resolve("pelne.jpg");
        ThumbnailMakerTest.writePhoto(full, 400, 300, true);
        byte[] jpeg = Files.readAllBytes(full);
        Files.createDirectories(phone);
        Files.write(phone.resolve("IMG_ok.jpg"), jpeg);
        Files.write(phone.resolve("IMG_cut.jpg"), java.util.Arrays.copyOf(jpeg, jpeg.length - 100));
        byte[] mp4 = MediaIntegrityTest.mp4(5000);
        Files.write(phone.resolve("VID_ok.mp4"), mp4);
        Files.write(phone.resolve("VID_cut.mp4"), java.util.Arrays.copyOf(mp4, 3000));

        PhoneArchiver.Summary summary = run(phone, archive, true);

        assertEquals(2, summary.moved().size(), summary.toString());
        assertEquals(List.of(phone.resolve("IMG_cut.jpg"), phone.resolve("VID_cut.mp4")), summary.incomplete());
        assertTrue(Files.exists(phone.resolve("IMG_cut.jpg")), "niekompletny zostaje w źródle");
        assertTrue(Files.exists(phone.resolve("VID_cut.mp4")));
        assertTrue(PhoneArchiveModule.summaryText(summary).contains("Pominięto niekompletne pliki: 2"));
    }

    @Test
    void stopsWhenThumbnailDiskIsFull() throws Exception {
        Path phone = dir.resolve("Telefon");
        ThumbnailMakerTest.writePhoto(phone.resolve("IMG_1.jpg"), 400, 300, true);
        ThumbnailMakerTest.writePhoto(phone.resolve("IMG_2.jpg"), 400, 300, true);
        Path archive = dir.resolve("Archiwum");
        PhoneArchiveOptions options = new PhoneArchiveOptions(phone, archive, true, true, 100,
                dir.resolve("Miniatury"), PhoneDuplicateAction.REMOVE, null);
        PhoneArchiver.InsufficientSpaceException error = assertThrows(PhoneArchiver.InsufficientSpaceException.class,
                () -> new PhoneArchiver(options, TODAY, null).withMinFreeBytes(Long.MAX_VALUE / 2)
                        .run((m, d, t) -> { }, () -> false));
        assertTrue(error.getMessage().startsWith("Za mało miejsca na dysku miniatur"), error.getMessage());
        assertTrue(Files.exists(phone.resolve("IMG_2.jpg")), "dalsze pliki zostają w źródle");
    }

    private static PhoneArchiver.Summary run(Path phone, Path archive, boolean subfolders) {
        return new PhoneArchiver(new PhoneArchiveOptions(phone, archive, subfolders), TODAY, null)
                .run((message, done, total) -> { }, () -> false);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
