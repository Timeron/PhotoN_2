package com.photon.backup;

import com.photon.phonearchive.PhoneArchiveOptions;
import com.photon.phonearchive.PhoneArchiver;
import com.photon.phonearchive.PhoneDuplicateAction;
import com.photon.thumbnails.ThumbnailMakerTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveWatcherTest {

    private static final Duration QUIET = Duration.ofSeconds(50);

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-18T10:00:00Z"));

    @Test
    void waitsUntilFileStopsChanging() throws IOException {
        Path phone = dir.resolve("Telefon");
        Path archive = dir.resolve("Archiwum");
        Files.createDirectories(archive.resolve("2020-01-01"));
        write(phone.resolve("IMG_1.jpg"), "początek");
        ArchiveWatcher watcher = watcher(phone, archive, PhoneDuplicateAction.REMOVE, null);

        // Pierwsza obserwacja — plik mógł właśnie zacząć się wgrywać.
        ArchiveWatcher.CycleReport first = watcher.runCycle(() -> false);
        assertEquals(0, first.archived());
        assertEquals(1, first.waiting());

        // Minutę później plik urósł — nadal trwa przesyłanie.
        clock.advance(Duration.ofMinutes(1));
        write(phone.resolve("IMG_1.jpg"), "początek i dalsza część");
        ArchiveWatcher.CycleReport second = watcher.runCycle(() -> false);
        assertEquals(1, second.waiting());
        assertTrue(Files.exists(phone.resolve("IMG_1.jpg")));

        // Kolejna minuta bez zmian — plik gotowy.
        clock.advance(Duration.ofMinutes(1));
        ArchiveWatcher.CycleReport third = watcher.runCycle(() -> false);
        assertEquals(1, third.archived());
        assertEquals(0, third.waiting());
        assertEquals("początek i dalsza część", Files.readString(archive.resolve("2026-11-18/IMG_1.jpg")));
    }

    @Test
    void emptyAndLockedFilesWait() throws IOException {
        Path phone = dir.resolve("Telefon");
        Path archive = dir.resolve("Archiwum");
        write(phone.resolve("pusty.jpg"), "");
        write(phone.resolve("zablokowany.mp4"), "film w trakcie zapisu");
        ArchiveWatcher watcher = watcher(phone, archive, PhoneDuplicateAction.REMOVE, null);
        watcher.runCycle(() -> false);
        clock.advance(Duration.ofMinutes(1));

        try (FileChannel channel = FileChannel.open(phone.resolve("zablokowany.mp4"),
                StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            assertNotNull(lock);
            ArchiveWatcher.CycleReport report = watcher.runCycle(() -> false);
            assertEquals(0, report.archived());
            assertEquals(2, report.waiting());
        }

        clock.advance(Duration.ofMinutes(1));
        ArchiveWatcher.CycleReport afterUnlock = watcher.runCycle(() -> false);
        assertEquals(1, afterUnlock.archived(), "po zwolnieniu blokady plik trafia do archiwum");
        assertTrue(Files.exists(phone.resolve("pusty.jpg")), "pusty plik czeka");
    }

    @Test
    void reportsDuplicateOnceAndRemovesItOnlyOnRequest() throws IOException {
        Path phone = dir.resolve("Telefon");
        Path archive = dir.resolve("Archiwum");
        write(phone.resolve("IMG_1.jpg"), "już jest");
        write(archive.resolve("2020-01-01/IMG_1.jpg"), "już jest");
        ArchiveWatcher watcher = watcher(phone, archive, PhoneDuplicateAction.REMOVE, null);

        watcher.runCycle(() -> false);
        clock.advance(Duration.ofMinutes(1));
        ArchiveWatcher.CycleReport report = watcher.runCycle(() -> false);
        assertEquals(1, report.newDuplicates().size());
        assertTrue(Files.exists(phone.resolve("IMG_1.jpg")), "nic nie jest usuwane bez pytania");

        clock.advance(Duration.ofMinutes(1));
        ArchiveWatcher.CycleReport again = watcher.runCycle(() -> false);
        assertTrue(again.newDuplicates().isEmpty(), "duplikat zgłaszany tylko raz");
        assertFalse(again.eventful());
        assertEquals(1, watcher.pendingDuplicates().size());
        assertEquals(1, watcher.pendingDuplicatesCountCached());

        PhoneArchiver.RemovalOutcome outcome = watcher.removePending(watcher.pendingDuplicates(), false);
        assertEquals(1, outcome.removed().size());
        assertFalse(Files.exists(phone.resolve("IMG_1.jpg")));
        assertTrue(Files.exists(archive.resolve("2020-01-01/IMG_1.jpg")));
        assertTrue(watcher.pendingDuplicates().isEmpty());
    }

    @Test
    void movesDuplicatesAutomaticallyWhenProfileSaysSo() throws IOException {
        Path phone = dir.resolve("Telefon");
        Path archive = dir.resolve("Archiwum");
        Path dups = dir.resolve("Duplikaty");
        write(phone.resolve("Camera/IMG_1.jpg"), "już jest");
        write(archive.resolve("2020-01-01/IMG_1.jpg"), "już jest");
        ArchiveWatcher watcher = watcher(phone, archive, PhoneDuplicateAction.MOVE_TO_FOLDER, dups);

        watcher.runCycle(() -> false);
        clock.advance(Duration.ofMinutes(1));
        ArchiveWatcher.CycleReport report = watcher.runCycle(() -> false);

        assertNotNull(report.movedDuplicates());
        assertEquals(1, report.movedDuplicates().removed().size());
        assertTrue(Files.exists(dups.resolve("Camera/IMG_1.jpg")));
        assertFalse(Files.exists(phone.resolve("Camera/IMG_1.jpg")));
        assertTrue(watcher.pendingDuplicates().isEmpty());
    }

    @Test
    void makesThumbnailsLikeTheArchiveTab() throws IOException {
        Path phone = dir.resolve("Telefon");
        Path archive = dir.resolve("Archiwum");
        Path thumbs = dir.resolve("Miniatury");
        ThumbnailMakerTest.writePhoto(phone.resolve("IMG_1.jpg"), 1600, 1200, true);
        PhoneArchiveOptions options = new PhoneArchiveOptions(phone, archive, true, true, 400, thumbs,
                PhoneDuplicateAction.REMOVE, null);
        ArchiveWatcher watcher = new ArchiveWatcher("Test", options, null, QUIET, clock);

        watcher.runCycle(() -> false);
        clock.advance(Duration.ofMinutes(1));
        ArchiveWatcher.CycleReport report = watcher.runCycle(() -> false);

        assertEquals(1, report.thumbnails());
        assertTrue(Files.exists(thumbs.resolve("2026-11-18/IMG_1.jpg")));
    }

    @Test
    void unavailableSourceSkipsCycleWithoutFailing() throws IOException {
        Path phone = dir.resolve("Telefon");
        Files.createDirectories(phone);
        ArchiveWatcher watcher = watcher(phone, dir.resolve("Archiwum"), PhoneDuplicateAction.REMOVE, null);
        Files.delete(phone); // np. telefon odłączony

        ArchiveWatcher.CycleReport report = watcher.runCycle(() -> false);

        assertNull(report.summary());
        assertNotNull(report.problem());
        assertTrue(AgentLoop.describe(report).startsWith("Pominięto sprawdzenie"));
    }

    @Test
    void activityMeansNewOrChangingFilesNotStuckOnes() throws IOException {
        Path phone = dir.resolve("Telefon");
        write(phone.resolve("pusty.jpg"), "");
        ArchiveWatcher watcher = watcher(phone, dir.resolve("Archiwum"), PhoneDuplicateAction.REMOVE, null);

        assertTrue(watcher.runCycle(() -> false).activity(), "nowy plik to aktywność");
        clock.advance(Duration.ofSeconds(15));
        ArchiveWatcher.CycleReport stuck = watcher.runCycle(() -> false);
        assertEquals(1, stuck.waiting());
        assertFalse(stuck.activity(), "pusty plik bez zmian nie trzyma szybkiego tempa");

        clock.advance(Duration.ofSeconds(15));
        write(phone.resolve("pusty.jpg"), "przesyłanie trwa");
        assertTrue(watcher.runCycle(() -> false).activity(), "rosnący plik to aktywność");
    }

    @Test
    void zeroQuietPeriodArchivesImmediately() throws IOException {
        Path phone = dir.resolve("Telefon");
        write(phone.resolve("IMG_1.jpg"), "od razu");
        ArchiveWatcher watcher = new ArchiveWatcher("Test",
                new PhoneArchiveOptions(phone, dir.resolve("Archiwum"), true), null, Duration.ZERO, clock);
        assertEquals(1, watcher.runCycle(() -> false).archived());
    }

    @Test
    void unavailableArchiveSkipsCycleAndResumesLater() throws IOException {
        Path phone = dir.resolve("Telefon");
        Path archive = dir.resolve("Archiwum");
        Files.createDirectories(archive.resolve("2020-01-01"));
        write(phone.resolve("IMG_1.jpg"), "1");
        ArchiveWatcher watcher = new ArchiveWatcher("Test", new PhoneArchiveOptions(phone, archive, true), null,
                Duration.ZERO, clock);
        assertEquals(1, watcher.runCycle(() -> false).archived());

        // Dysk sieciowy odłączony; w miejscu archiwum pusty folder (np. punkt montowania).
        Path disconnected = dir.resolve("Archiwum-odlaczone");
        Files.move(archive, disconnected);
        Files.createDirectories(archive);
        write(phone.resolve("IMG_2.jpg"), "2");
        ArchiveWatcher.CycleReport skipped = watcher.runCycle(() -> false);
        assertNotNull(skipped.problem());
        assertTrue(skipped.problem().contains("Pliki zostały w folderze archiwizowanym"), skipped.problem());
        assertTrue(Files.exists(phone.resolve("IMG_2.jpg")));
        assertFalse(Files.exists(archive.resolve("2026-11-18")), "nic nie trafiło do pustego archiwum");

        // Dysk wrócił.
        Files.delete(archive);
        Files.move(disconnected, archive);
        ArchiveWatcher.CycleReport resumed = watcher.runCycle(() -> false);
        assertNull(resumed.problem());
        assertEquals(1, resumed.archived());
        assertTrue(Files.exists(archive.resolve("2026-11-18/IMG_2.jpg")));
    }

    @Test
    void truncatedUploadWaitsAndIsReportedAsStalled() throws Exception {
        Path phone = dir.resolve("Telefon");
        Path archive = dir.resolve("Archiwum");
        Files.createDirectories(archive.resolve("2020-01-01"));
        Path full = dir.resolve("pelne.jpg");
        ThumbnailMakerTest.writePhoto(full, 400, 300, true);
        byte[] bytes = Files.readAllBytes(full);
        // Telefon przerwał wysyłanie w połowie — plik przestał rosnąć, ale jest ucięty.
        Files.createDirectories(phone);
        Files.write(phone.resolve("IMG_1.jpg"), java.util.Arrays.copyOf(bytes, bytes.length / 2));
        ArchiveWatcher watcher = watcher(phone, archive, PhoneDuplicateAction.REMOVE, null);

        watcher.runCycle(() -> false);
        clock.advance(Duration.ofMinutes(5));
        ArchiveWatcher.CycleReport report = watcher.runCycle(() -> false);
        assertEquals(0, report.archived(), "ucięty plik nie trafia do archiwum, mimo że się nie zmienia");
        assertEquals(1, report.waiting());
        assertEquals(List.of(phone.resolve("IMG_1.jpg")), report.summary().incomplete());
        assertTrue(report.stalled().isEmpty(), "jeszcze nie zgłaszany");

        clock.advance(ArchiveWatcher.STALL_AFTER);
        ArchiveWatcher.CycleReport stalled = watcher.runCycle(() -> false);
        assertEquals(List.of(phone.resolve("IMG_1.jpg")), stalled.stalled());

        // Telefon wysłał plik ponownie — cały.
        Files.write(phone.resolve("IMG_1.jpg"), bytes);
        watcher.runCycle(() -> false);
        clock.advance(Duration.ofMinutes(1));
        ArchiveWatcher.CycleReport done = watcher.runCycle(() -> false);
        assertEquals(1, done.archived());
        assertTrue(done.stalled().isEmpty());
        assertTrue(Files.exists(archive.resolve("2026-11-18/IMG_1.jpg")));
    }

    private ArchiveWatcher watcher(Path phone, Path archive, PhoneDuplicateAction action, Path dups) {
        PhoneArchiveOptions options = new PhoneArchiveOptions(phone, archive, true, false, 800, null, action, dups);
        return new ArchiveWatcher("Test", options, null, QUIET, clock);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** Zegar przesuwany ręcznie w teście. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
