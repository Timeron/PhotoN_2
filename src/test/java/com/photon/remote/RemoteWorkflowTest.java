package com.photon.remote;

import com.photon.backup.ArchiveWatcher;
import com.photon.duplicates.DuplicateAction;
import com.photon.duplicates.KeepStrategy;
import com.photon.duplicates.SearchMethod;
import com.photon.duplicates.DuplicateSearchOptions;
import com.photon.duplicates.scan.DuplicateFileActions;
import com.photon.duplicates.scan.DuplicateScanner;
import com.photon.duplicates.scan.ScanResult;
import com.photon.phonearchive.PhoneArchiveOptions;
import com.photon.phonearchive.PhoneArchiver;
import com.photon.phonearchive.SourceDuplicates;
import com.photon.phonearchive.PhoneDuplicateAction;
import com.photon.thumbnails.ThumbnailGenerator;
import com.photon.thumbnails.ThumbnailMakerTest;
import com.photon.thumbnails.ThumbnailOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Archiwizacja, miniatury i duplikaty na folderach z serwerów FTP i SFTP. */
class RemoteWorkflowTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 19);

    @TempDir
    Path dir;

    private TestServers servers;
    private Path ftpRoot;
    private Path sftpRoot;

    @BeforeEach
    void setUp() throws Exception {
        servers = new TestServers();
        ftpRoot = dir.resolve("ftp");
        sftpRoot = dir.resolve("sftp");
        RemoteServers.useFile(dir.resolve("servers.json"));
        RemoteServers.save(List.of(servers.ftp("Telefon FTP", ftpRoot),
                servers.sftp("NAS", sftpRoot, dir.resolve("hostkey.ser"))));
    }

    @AfterEach
    void tearDown() {
        servers.close();
        RemoteServers.useFile(null);
    }

    @Test
    void archivesPhotosUploadedToFtpIntoLocalArchive() throws Exception {
        ThumbnailMakerTest.writePhoto(ftpRoot.resolve("Camera/IMG_1.jpg"), 400, 300, true);
        Files.writeString(ftpRoot.resolve("Camera/VID_2.mp4"), "film");
        Path archive = dir.resolve("Archiwum");
        Files.createDirectories(archive.resolve("2020-01-01"));
        Files.writeString(archive.resolve("2020-01-01/stary.mp4"), "film"); // duplikat filmu z serwera
        Path thumbs = dir.resolve("Miniatury");

        PhoneArchiveOptions options = new PhoneArchiveOptions(Locations.parse("[Telefon FTP]/"), archive, true,
                true, 100, thumbs, PhoneDuplicateAction.REMOVE, null);
        assertEquals(null, options.validate());
        PhoneArchiver.Summary summary = new PhoneArchiver(options, TODAY, null).run((m, d, t) -> { }, () -> false);

        assertTrue(summary.errors().isEmpty(), summary.errors().toString());
        assertEquals(1, summary.moved().size());
        assertTrue(Files.exists(archive.resolve("2026-11-19/IMG_1.jpg")));
        assertFalse(Files.exists(ftpRoot.resolve("Camera/IMG_1.jpg")), "przeniesione z serwera");
        assertEquals(1, summary.thumbnails().created());
        assertTrue(Files.exists(thumbs.resolve("2026-11-19/IMG_1.jpg")));

        assertEquals(1, summary.duplicates().size());
        PhoneArchiver.RemovalOutcome removed = SourceDuplicates.remove(summary.duplicates(), true);
        assertTrue(removed.errors().isEmpty(), removed.errors().toString());
        assertFalse(Files.exists(ftpRoot.resolve("Camera/VID_2.mp4")), "duplikat usunięty z serwera (bez kosza)");
    }

    @Test
    void archivesIntoSftpArchiveAndMovesDuplicatesOnServer() throws Exception {
        Path phone = dir.resolve("Telefon");
        Files.createDirectories(phone);
        Files.writeString(phone.resolve("IMG_1.jpg"), "nowe");
        Files.writeString(phone.resolve("IMG_2.jpg"), "już w archiwum");
        Files.createDirectories(sftpRoot.resolve("Archiwum/2020-01-01"));
        Files.writeString(sftpRoot.resolve("Archiwum/2020-01-01/IMG_2.jpg"), "już w archiwum");

        Path archive = Locations.parse("[NAS]/Archiwum");
        Path trash = Locations.parse("[NAS]/Smietnik");
        PhoneArchiveOptions options = new PhoneArchiveOptions(phone, archive, true, false, 800, null,
                PhoneDuplicateAction.MOVE_TO_FOLDER, trash);
        assertEquals(null, options.validate());
        PhoneArchiver.Summary summary = new PhoneArchiver(options, TODAY, null).expectingDateFolders(true)
                .run((m, d, t) -> { }, () -> false);

        assertTrue(summary.errors().isEmpty(), summary.errors().toString());
        assertEquals(1, summary.dateFolders());
        assertEquals("nowe", Files.readString(sftpRoot.resolve("Archiwum/2026-11-19/IMG_1.jpg")));
        assertFalse(Files.exists(phone.resolve("IMG_1.jpg")));
        assertFalse(Files.list(sftpRoot.resolve("Archiwum/2026-11-19")).anyMatch(p -> p.toString().endsWith(".photon-tmp")));

        assertEquals(1, summary.duplicates().size());
        PhoneArchiver.RemovalOutcome moved = SourceDuplicates.move(summary.duplicates(), phone, trash);
        assertTrue(moved.errors().isEmpty(), moved.errors().toString());
        assertEquals("już w archiwum", Files.readString(sftpRoot.resolve("Smietnik/IMG_2.jpg")));
        assertFalse(Files.exists(phone.resolve("IMG_2.jpg")));
    }

    @Test
    void backupAgentCycleWorksWithServerSource() throws Exception {
        Files.createDirectories(ftpRoot.resolve("upload"));
        Files.writeString(ftpRoot.resolve("upload/IMG_1.jpg"), "zdjęcie");
        Path archive = dir.resolve("Archiwum");
        Files.createDirectories(archive);
        ArchiveWatcher watcher = new ArchiveWatcher("Test",
                new PhoneArchiveOptions(Locations.parse("[Telefon FTP]/upload"), archive, true), null,
                Duration.ZERO, Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC));

        ArchiveWatcher.CycleReport report = watcher.runCycle(() -> false);
        assertEquals(null, report.problem());
        assertEquals(1, report.archived(), report.errors().toString());
        assertEquals("zdjęcie", Files.readString(archive.resolve("2026-11-19/IMG_1.jpg")));

        // Serwer wyłączony — przebieg pomijany, nic nie ginie.
        servers.close();
        ArchiveWatcher.CycleReport offline = watcher.runCycle(() -> false);
        assertNotNull(offline.problem());
    }

    @Test
    void findsDuplicatesAcrossServerAndDisk() throws Exception {
        Path local = dir.resolve("Zdjecia");
        ThumbnailMakerTest.writePhoto(local.resolve("a.jpg"), 300, 200, true);
        Files.createDirectories(sftpRoot.resolve("Kopia"));
        Files.copy(local.resolve("a.jpg"), sftpRoot.resolve("Kopia/a-kopia.jpg"));
        Path remote = Locations.parse("[NAS]/Kopia");

        for (SearchMethod method : List.of(SearchMethod.EXACT, SearchMethod.SIMILAR, SearchMethod.METADATA)) {
            DuplicateSearchOptions options = new DuplicateSearchOptions(List.of(local, remote), List.of(), true, true,
                    false, Set.of("jpg"), method, 90, 0, 0, null, null, KeepStrategy.LOCATION_PRIORITY,
                    DuplicateAction.REPORT_ONLY, null, false, 2, false, true);
            ScanResult result = new DuplicateScanner(options, null).scan((m, d, t) -> { }, () -> false);
            assertEquals(1, result.groups().size(), method + " " + result.errors());
            assertEquals(local.resolve("a.jpg"), result.groups().get(0).original().path());
        }

        DuplicateSearchOptions options = new DuplicateSearchOptions(List.of(local, remote), List.of(), true, true,
                false, Set.of("jpg"), SearchMethod.EXACT, 90, 0, 0, null, null, KeepStrategy.LOCATION_PRIORITY,
                DuplicateAction.REPORT_ONLY, null, false, 2, false, true);
        ScanResult result = new DuplicateScanner(options, null).scan((m, d, t) -> { }, () -> false);
        DuplicateFileActions.Outcome outcome = DuplicateFileActions.apply(result.groups(),
                DuplicateFileActions.Mode.TRASH, null, List.of(local, remote), null, () -> false);
        assertTrue(outcome.errors().isEmpty(), outcome.errors().toString());
        assertFalse(Files.exists(sftpRoot.resolve("Kopia/a-kopia.jpg")), "kopia na serwerze usunięta");
        assertTrue(Files.exists(local.resolve("a.jpg")));
    }

    @Test
    void makesThumbnailsFromServerFolderIntoServerFolder() throws Exception {
        ThumbnailMakerTest.writePhoto(ftpRoot.resolve("Zdjecia/IMG_1.jpg"), 800, 600, true);
        ThumbnailOptions options = new ThumbnailOptions(Locations.parse("[Telefon FTP]/Zdjecia"),
                Locations.parse("[NAS]/Miniatury"), 200, true, false);
        assertEquals(null, options.validate());
        ThumbnailGenerator.Summary summary = new ThumbnailGenerator(options, 2).run((m, d, t) -> { }, () -> false);
        assertTrue(summary.errors().isEmpty(), summary.errors().toString());
        assertEquals(1, summary.created());
        Path thumbnail = sftpRoot.resolve("Miniatury/IMG_1.jpg");
        assertTrue(Files.exists(thumbnail));
        assertTrue(Files.size(thumbnail) < Files.size(ftpRoot.resolve("Zdjecia/IMG_1.jpg")));
    }
}
