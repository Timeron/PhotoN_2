package com.photon.backup;

import com.photon.phonearchive.PhoneArchiveOptions;
import com.photon.phonearchive.PhoneDuplicateAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.util.WaitForAsyncUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pętla agenta w tym samym procesie (wątek), z krótkimi odstępami — bez czekania minutami. */
class AgentLoopTest {

    @TempDir
    Path dir;

    private Thread thread;
    private AgentLoop loop;
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    @AfterEach
    void stopAgent() throws InterruptedException {
        if (loop != null) {
            loop.requestStop();
            thread.join(10_000);
        }
    }

    @Test
    void archivesReportsStatusAndObeysCommands() throws Exception {
        Path upload = dir.resolve("Upload");
        Path archive = dir.resolve("Archiwum");
        write(upload.resolve("IMG_1.jpg"), "jeden");
        write(upload.resolve("IMG_dup.jpg"), "już jest");
        write(archive.resolve("2020-01-01/IMG_dup.jpg"), "już jest");
        AgentFiles files = AgentFiles.in(dir.resolve("backup"));
        AgentControl control = new AgentControl(files, null);

        start(new PhoneArchiveOptions(upload, archive, true, false, 800, null, PhoneDuplicateAction.REMOVE, null),
                files, Duration.ofMillis(300));

        Path day = archive.resolve(LocalDate.now().format(com.photon.phonearchive.PhoneArchiver.DAY_FORMAT));
        waitFor(() -> Files.exists(day.resolve("IMG_1.jpg")));
        assertTrue(control.isRunning());

        waitFor(() -> control.readStatus().map(s -> s.pendingDuplicates().size() == 1).orElse(false));
        AgentStatus status = control.readStatus().orElseThrow();
        assertEquals(AgentStatus.State.RUNNING, status.state());
        assertEquals("Test", status.profile());
        assertEquals(1, status.archived());
        assertEquals(ProcessHandle.current().pid(), status.pid());
        assertTrue(status.notifications(), "powiadomienia o nowych plikach aktywne");

        // Drugi agent się nie uruchomi.
        AgentLoop second = new AgentLoop("Inny", new PhoneArchiveOptions(upload, archive, true), files, null,
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ZERO,
                Duration.ofSeconds(1), Clock.systemDefaultZone(), null);
        assertThrows(AgentLoop.AlreadyRunningException.class, second::run);

        // Usunięcie zgłoszonego duplikatu poleceniem.
        control.send(AgentCommand.removeDuplicates(List.of(upload.resolve("IMG_dup.jpg")), false));
        waitFor(() -> !Files.exists(upload.resolve("IMG_dup.jpg")));
        assertTrue(Files.exists(archive.resolve("2020-01-01/IMG_dup.jpg")));
        waitFor(() -> control.readStatus().map(s -> s.pendingDuplicates().isEmpty()).orElse(false));

        // Zatrzymanie poleceniem.
        control.send(AgentCommand.of(AgentCommand.Type.STOP));
        thread.join(10_000);
        assertFalse(thread.isAlive());
        assertFalse(control.isRunning(), "blokada zwolniona");
        assertEquals(AgentStatus.State.STOPPED, control.readStatus().orElseThrow().state());
        List<String> log = control.logTail(50);
        assertTrue(log.get(0).contains("Agent zatrzymany"), log.toString());
        assertTrue(log.stream().anyMatch(l -> l.contains("Zarchiwizowano 1 plik")), log.toString());
        assertTrue(failure.get() == null, String.valueOf(failure.get()));
        loop = null;
    }

    @Test
    void newFileWakesAgentImmediatelyEvenWhenNextCheckIsFarAway() throws Exception {
        Path upload = Files.createDirectories(dir.resolve("Upload"));
        Path archive = dir.resolve("Archiwum");
        AgentFiles files = AgentFiles.in(dir.resolve("backup"));
        AgentControl control = new AgentControl(files, null);
        // Sprawdzanie co minutę — bez powiadomienia plik czekałby długo.
        start(new PhoneArchiveOptions(upload, archive, true), files, Duration.ofMinutes(1));
        waitFor(() -> control.readStatus().map(s -> s.lastCheck() != null && s.notifications()).orElse(false));

        Instant before = Instant.now();
        write(upload.resolve("Camera/IMG_new.jpg"), "nowy");
        Path day = archive.resolve(LocalDate.now().format(com.photon.phonearchive.PhoneArchiver.DAY_FORMAT));
        waitFor(() -> Files.exists(day.resolve("IMG_new.jpg")));
        assertTrue(Duration.between(before, Instant.now()).toSeconds() < 20, "obudzony powiadomieniem");
    }

    @Test
    void checkNowCommandRunsCheckImmediately() throws Exception {
        Path upload = Files.createDirectories(dir.resolve("Upload"));
        Path archive = dir.resolve("Archiwum");
        AgentFiles files = AgentFiles.in(dir.resolve("backup"));
        AgentControl control = new AgentControl(files, null);
        start(new PhoneArchiveOptions(upload, archive, false), files, Duration.ofMinutes(1));
        waitFor(() -> control.readStatus().map(s -> s.lastCheck() != null).orElse(false));
        Instant first = control.readStatus().orElseThrow().lastCheck();

        control.send(AgentCommand.of(AgentCommand.Type.CHECK_NOW));
        waitFor(() -> control.readStatus().map(s -> s.lastCheck().isAfter(first)).orElse(false));
    }

    @Test
    void ignoresCommandsSentBeforeStart() throws Exception {
        Path upload = Files.createDirectories(dir.resolve("Upload"));
        AgentFiles files = AgentFiles.in(dir.resolve("backup"));
        AgentControl control = new AgentControl(files, null);
        control.send(new AgentCommand(AgentCommand.Type.STOP, List.of(), false, Instant.now().minusSeconds(60)));

        start(new PhoneArchiveOptions(upload, dir.resolve("Archiwum"), true), files, Duration.ofMillis(300));
        waitFor(() -> control.readStatus().map(s -> s.lastCheck() != null).orElse(false));
        Thread.sleep(500);
        assertTrue(control.isRunning(), "stare polecenie STOP nie zatrzymało agenta");
        try (var commands = Files.list(files.commands())) {
            assertEquals(0, commands.count(), "stare polecenie usunięte");
        }
    }

    @Test
    void unavailableSourceIsReportedOnceAndAgentKeepsRunning() throws Exception {
        Path upload = Files.createDirectories(dir.resolve("Upload"));
        AgentFiles files = AgentFiles.in(dir.resolve("backup"));
        AgentControl control = new AgentControl(files, null);
        start(new PhoneArchiveOptions(upload, dir.resolve("Archiwum"), true), files, Duration.ofMillis(200));
        waitFor(() -> control.readStatus().map(s -> s.lastCheck() != null).orElse(false));

        Files.delete(upload); // np. odłączony dysk
        waitFor(() -> control.readStatus().map(s -> s.lastProblem() != null).orElse(false));
        Thread.sleep(1000);
        assertTrue(control.isRunning());
        long reported = control.logTail(100).stream().filter(l -> l.contains("Pominięto sprawdzenie")).count();
        assertEquals(1, reported, "ten sam problem nie jest powtarzany co sprawdzenie");

        Files.createDirectories(upload);
        waitFor(() -> control.readStatus().map(s -> s.lastProblem() == null).orElse(false));
        assertTrue(control.logTail(100).stream().anyMatch(l -> l.contains("znowu dostępny")));
    }

    private void start(PhoneArchiveOptions options, AgentFiles files, Duration fast) {
        loop = new AgentLoop("Test", options, files, null, fast, fast, Duration.ofHours(1), Duration.ZERO,
                Duration.ofMillis(500), Clock.systemDefaultZone(), null);
        thread = new Thread(() -> {
            try {
                loop.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "agent-test");
        thread.setDaemon(true);
        thread.start();
    }

    private static void waitFor(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        WaitForAsyncUtils.waitFor(20, TimeUnit.SECONDS, condition);
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
