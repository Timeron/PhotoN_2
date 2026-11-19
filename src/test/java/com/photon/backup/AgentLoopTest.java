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
import com.photon.phonearchive.PhoneArchiver;
import java.io.FileInputStream;
import java.nio.file.attribute.FileTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

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

        Path day = archive.resolve(LocalDate.now().format(PhoneArchiver.DAY_FORMAT));
        waitFor(() -> Files.exists(day.resolve("IMG_1.jpg")));
        assertTrue(control.isRunning());

        waitFor(() -> control.readStatus().map(s -> s.pendingDuplicates().size() == 1).orElse(false));
        AgentStatus status = control.readStatus().orElseThrow();
        assertEquals(AgentStatus.State.RUNNING, status.state());
        assertEquals("Test", status.profiles().get(0).profile());
        assertEquals(1, status.archived());
        assertEquals(ProcessHandle.current().pid(), status.pid());
        assertTrue(status.profiles().get(0).schedule().notifications(), "powiadomienia o nowych plikach aktywne");

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
        waitFor(() -> control.readStatus().map(s -> s.profiles().get(0).schedule().lastCheck() != null && s.profiles().get(0).schedule().notifications()).orElse(false));

        Instant before = Instant.now();
        write(upload.resolve("Camera/IMG_new.jpg"), "nowy");
        Path day = archive.resolve(LocalDate.now().format(PhoneArchiver.DAY_FORMAT));
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
        waitFor(() -> control.readStatus().map(s -> s.profiles().get(0).schedule().lastCheck() != null).orElse(false));
        Instant first = control.readStatus().orElseThrow().profiles().get(0).schedule().lastCheck();

        control.send(AgentCommand.of(AgentCommand.Type.CHECK_NOW));
        waitFor(() -> control.readStatus().map(s -> s.profiles().get(0).schedule().lastCheck().isAfter(first)).orElse(false));
    }

    @Test
    void ignoresCommandsSentBeforeStart() throws Exception {
        Path upload = Files.createDirectories(dir.resolve("Upload"));
        AgentFiles files = AgentFiles.in(dir.resolve("backup"));
        AgentControl control = new AgentControl(files, null);
        control.send(new AgentCommand(AgentCommand.Type.STOP, List.of(), false, Instant.now().minusSeconds(60)));

        start(new PhoneArchiveOptions(upload, dir.resolve("Archiwum"), true), files, Duration.ofMillis(300));
        waitFor(() -> control.readStatus().map(s -> s.profiles().get(0).schedule().lastCheck() != null).orElse(false));
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
        waitFor(() -> control.readStatus().map(s -> s.profiles().get(0).schedule().lastCheck() != null).orElse(false));

        Files.delete(upload); // np. odłączony dysk
        waitFor(() -> control.readStatus().map(s -> s.profiles().get(0).lastProblem() != null).orElse(false));
        Thread.sleep(1000);
        assertTrue(control.isRunning());
        long reported = control.logTail(100).stream().filter(l -> l.contains("Pominięto sprawdzenie")).count();
        assertEquals(1, reported, "ten sam problem nie jest powtarzany co sprawdzenie");

        Files.createDirectories(upload);
        waitFor(() -> control.readStatus().map(s -> s.profiles().get(0).lastProblem() == null).orElse(false));
        assertTrue(control.logTail(100).stream().anyMatch(l -> l.contains("znowu dostępne")));
    }

    @Test
    void idleAgentWaitsForEventsWithoutTouchingDisk() throws Exception {
        Path upload = Files.createDirectories(dir.resolve("Upload"));
        Path archive = dir.resolve("Archiwum");
        AgentFiles files = AgentFiles.in(dir.resolve("backup"));
        AgentControl control = new AgentControl(files, null);
        // Sprawdzanie co 200 ms — bez trybu zdarzeń przez 2 s byłoby ~10 sprawdzeń i zapisów stanu.
        start(new PhoneArchiveOptions(upload, archive, true), files, Duration.ofMillis(200), Duration.ZERO, null);
        waitFor(() -> control.readStatus().map(s -> s.profiles().get(0).schedule().eventDriven()).orElse(false));
        AgentStatus idle = control.readStatus().orElseThrow();
        assertEquals(null, idle.profiles().get(0).schedule().nextCheck(), "bez kontrolnego sprawdzania");
        assertTrue(idle.profiles().get(0).schedule().nextCheckText(DateTimeFormatter.ISO_INSTANT).startsWith("przy nowym pliku"));
        String before = Files.readString(files.status());
        FileTime written = Files.getLastModifiedTime(files.status());

        Thread.sleep(2000);
        assertEquals(before, Files.readString(files.status()), "stan nie jest zapisywany, gdy nic się nie dzieje");
        assertEquals(written, Files.getLastModifiedTime(files.status()));

        // Nowy plik budzi agenta i jest archiwizowany.
        write(upload.resolve("IMG_1.jpg"), "nowy");
        Path day = archive.resolve(LocalDate.now().format(PhoneArchiver.DAY_FORMAT));
        waitFor(() -> Files.exists(day.resolve("IMG_1.jpg")));
        waitFor(() -> control.readStatus().map(s -> s.profiles().get(0).schedule().eventDriven() && s.archived() == 1).orElse(false));
        assertTrue(control.logTail(50).stream().anyMatch(l -> l.contains("Czekam na nowe pliki")));
    }

    @Test
    void fileBeingUploadedIsPolledUntilStableThenAgentSleepsAgain() throws Exception {
        Path upload = Files.createDirectories(dir.resolve("Upload"));
        Path archive = dir.resolve("Archiwum");
        AgentFiles files = AgentFiles.in(dir.resolve("backup"));
        AgentControl control = new AgentControl(files, null);
        start(new PhoneArchiveOptions(upload, archive, true), files, Duration.ofMillis(200), Duration.ofSeconds(1), null);
        waitFor(() -> control.readStatus().map(s -> s.profiles().get(0).schedule().eventDriven()).orElse(false));

        write(upload.resolve("VID_1.mp4"), "początek filmu");
        // Plik dopiero się pojawił — agent sprawdza go cyklicznie (co 200 ms), aż przez 1 s się nie zmieni.
        Path day = archive.resolve(LocalDate.now().format(PhoneArchiver.DAY_FORMAT));
        try {
            waitFor(() -> Files.exists(day.resolve("VID_1.mp4")));
        } catch (TimeoutException e) {
            throw new AssertionError("stan: " + control.readStatus() + "\ndziennik: " + control.logTail(30), e);
        }
        waitFor(() -> control.readStatus().map(s -> s.profiles().get(0).schedule().eventDriven() && s.profiles().get(0).waiting() == 0).orElse(false));
        assertTrue(control.logTail(50).stream().anyMatch(l -> l.contains("Plik w trakcie przesyłania")));
    }

    @Test
    void oneAgentWatchesSeveralProfilesEachWithItsOwnArchive() throws Exception {
        Path phone = Files.createDirectories(dir.resolve("Telefon"));
        Path tablet = Files.createDirectories(dir.resolve("Tablet"));
        Path phoneArchive = dir.resolve("ArchiwumTelefonu");
        Path tabletArchive = dir.resolve("ArchiwumTabletu");
        AgentFiles files = AgentFiles.in(dir.resolve("backup"));
        AgentControl control = new AgentControl(files, null);
        loop = new AgentLoop(List.of(
                new AgentProfile("Telefon", new PhoneArchiveOptions(phone, phoneArchive, true)),
                new AgentProfile("Tablet", new PhoneArchiveOptions(tablet, tabletArchive, true))),
                files, null, Duration.ofMillis(200), Duration.ofMillis(200), Duration.ofHours(1), Duration.ZERO,
                Duration.ofMillis(500), Clock.systemDefaultZone(), null).withSafetyCheck(null);
        thread = new Thread(() -> {
            try {
                loop.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "agent-test");
        thread.setDaemon(true);
        thread.start();
        waitFor(() -> control.readStatus().map(s -> s.profiles().size() == 2
                && s.profiles().stream().allMatch(p -> p.schedule().eventDriven())).orElse(false));

        write(phone.resolve("IMG_T.jpg"), "z telefonu");
        write(tablet.resolve("IMG_P.jpg"), "z tabletu");
        String day = LocalDate.now().format(com.photon.phonearchive.PhoneArchiver.DAY_FORMAT);
        waitFor(() -> Files.exists(phoneArchive.resolve(day).resolve("IMG_T.jpg")));
        waitFor(() -> Files.exists(tabletArchive.resolve(day).resolve("IMG_P.jpg")));
        assertFalse(Files.exists(phoneArchive.resolve(day).resolve("IMG_P.jpg")), "każdy profil do swojego archiwum");
        assertFalse(Files.exists(tabletArchive.resolve(day).resolve("IMG_T.jpg")));

        waitFor(() -> control.readStatus().map(s -> s.archived() == 2).orElse(false));
        AgentStatus status = control.readStatus().orElseThrow();
        assertEquals(1, status.profile("Telefon").archived());
        assertEquals(1, status.profile("Tablet").archived());
        List<String> log = control.logTail(50);
        assertTrue(log.stream().anyMatch(l -> l.contains("profile: Telefon, Tablet")), log.toString());
        assertTrue(log.stream().anyMatch(l -> l.contains("[Tablet] Zarchiwizowano 1 plik")), log.toString());
    }

    @Test
    void statusWriteBlockedByReaderIsRetried() throws Exception {
        Path upload = Files.createDirectories(dir.resolve("Upload"));
        Path archive = dir.resolve("Archiwum");
        AgentFiles files = AgentFiles.in(dir.resolve("backup"));
        AgentControl control = new AgentControl(files, null);
        start(new PhoneArchiveOptions(upload, archive, true), files, Duration.ofMillis(200), Duration.ZERO, null);
        waitFor(() -> control.readStatus().map(s -> s.profiles().get(0).schedule().eventDriven()).orElse(false));

        Path day = archive.resolve(LocalDate.now().format(PhoneArchiver.DAY_FORMAT));
        // Inny program (np. starsza wersja PhotoN) trzyma plik stanu otwarty przez java.io — w Windows to
        // blokuje podmianę pliku, więc zapis stanu po archiwizacji się nie uda.
        try (FileInputStream reader = new FileInputStream(files.status().toFile())) {
            write(upload.resolve("IMG_1.jpg"), "nowy");
            waitFor(() -> Files.exists(day.resolve("IMG_1.jpg")));
            Thread.sleep(1500);
        }
        // Po zwolnieniu pliku agent ponawia zapis sam — bez nowych plików i bez cyklicznego sprawdzania.
        waitFor(() -> control.readStatus().map(s -> s.archived() == 1 && !s.profiles().get(0).schedule().checking()).orElse(false));
    }

    @Test
    void sessionEndsAfterQuietTimeWithSummaryAndCsv() throws Exception {
        Path upload = Files.createDirectories(dir.resolve("Upload"));
        Path archive = dir.resolve("Archiwum");
        AgentFiles files = AgentFiles.in(dir.resolve("backup"));
        AgentControl control = new AgentControl(files, null);
        start(new PhoneArchiveOptions(upload, archive, true), files, Duration.ofMillis(200), Duration.ZERO, null);
        loop.withSessionEnd(Duration.ofSeconds(2));
        waitFor(() -> control.readStatus().map(s -> s.profiles().get(0).schedule().eventDriven()).orElse(false));

        write(upload.resolve("IMG_1.jpg"), "pierwszy");
        write(upload.resolve("IMG_2.jpg"), "drugi");
        waitFor(() -> control.readStatus().map(s -> s.profiles().get(0).session() != null && s.profiles().get(0).session().archived() == 2).orElse(false));
        assertTrue(control.readStatus().orElseThrow().profiles().get(0).sessionText(DateTimeFormatter.ISO_INSTANT)
                .contains("zarchiwizowano 2 pliki"));

        // 2 s bez nowych plików — koniec sesji: podsumowanie w dzienniku i stanie, lista plików w CSV.
        waitFor(() -> control.readStatus().map(s -> s.profiles().get(0).session() == null && s.profiles().get(0).lastSession() != null).orElse(false));
        assertTrue(control.readStatus().orElseThrow().profiles().get(0).lastSession().contains("zarchiwizowano 2 pliki"));
        assertTrue(control.logTail(20).stream().anyMatch(l -> l.contains("Sesja ") && l.contains("Lista plików")));
        Path sessions = dir.resolve("backup/sesje");
        try (var csv = Files.list(sessions)) {
            Path file = csv.findFirst().orElseThrow();
            String content = Files.readString(file);
            assertTrue(content.contains("zarchiwizowano") && content.contains("IMG_1.jpg") && content.contains("IMG_2.jpg"),
                    content);
        }
    }

    @Test
    void safetyCheckRunsEvenWithoutEvents() throws Exception {
        Path upload = Files.createDirectories(dir.resolve("Upload"));
        AgentFiles files = AgentFiles.in(dir.resolve("backup"));
        AgentControl control = new AgentControl(files, null);
        start(new PhoneArchiveOptions(upload, dir.resolve("Archiwum"), true), files, Duration.ofMillis(200),
                Duration.ZERO, Duration.ofMillis(700));
        waitFor(() -> control.readStatus().map(s -> s.profiles().get(0).schedule().eventDriven()).orElse(false));
        AgentStatus first = control.readStatus().orElseThrow();
        assertTrue(first.profiles().get(0).schedule().nextCheck() != null, "termin kontrolnego sprawdzenia");
        waitFor(() -> control.readStatus().map(s -> s.profiles().get(0).schedule().lastCheck().isAfter(first.profiles().get(0).schedule().lastCheck())).orElse(false));
    }

    @Test
    void settingsFileDefaultsToHourlySafetyCheck() throws IOException {
        AgentFiles files = AgentFiles.in(dir.resolve("backup"));
        AgentSettings defaults = AgentSettings.load(files);
        assertEquals(60, defaults.safetyCheckMinutes());
        assertEquals(Duration.ofHours(1), defaults.safetyCheck());

        assertEquals(Duration.ofMinutes(2), defaults.quietPeriod(), "domyślnie 2 minuty (wolny internet)");
        defaults.withSafetyCheckMinutes(0).withQuietSeconds(300).save(files);
        AgentSettings off = AgentSettings.load(files);
        assertEquals(null, off.safetyCheck(), "wyłączone");
        assertEquals(Duration.ofMinutes(5), off.quietPeriod());
        assertEquals(Duration.ofHours(1), off.slowPolling(), "bez powiadomień i tak co godzinę");
        assertEquals(Duration.ofMinutes(15), new AgentSettings(15, 30).slowPolling());
    }

    private void start(PhoneArchiveOptions options, AgentFiles files, Duration fast) {
        start(options, files, fast, Duration.ZERO, Duration.ofHours(1));
    }

    private void start(PhoneArchiveOptions options, AgentFiles files, Duration fast, Duration quiet, Duration safety) {
        loop = new AgentLoop("Test", options, files, null, fast, fast, Duration.ofHours(1), quiet,
                Duration.ofMillis(500), Clock.systemDefaultZone(), null).withSafetyCheck(safety);
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

    private void waitFor(Callable<Boolean> condition) throws Exception {
        try {
            WaitForAsyncUtils.waitFor(20, TimeUnit.SECONDS, condition);
        } catch (TimeoutException e) {
            AgentControl control = new AgentControl(AgentFiles.in(dir.resolve("backup")), null);
            throw new AssertionError("Nie doczekano się warunku. Stan: " + control.readStatus()
                    + "\nDziennik: " + control.logTail(30) + "\nBłąd wątku: " + failure.get()
                    + "\nStos agenta:\n" + (thread == null ? "-" : Arrays.stream(thread.getStackTrace())
                    .limit(40).map(f -> "  at " + f).collect(Collectors.joining("\n"))), e);
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
