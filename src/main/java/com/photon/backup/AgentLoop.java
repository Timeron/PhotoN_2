package com.photon.backup;

import com.photon.Json;
import com.photon.duplicates.scan.HashCache;
import com.photon.phonearchive.PhoneArchiveOptions;
import com.photon.phonearchive.PhoneArchiver;
import com.photon.ui.Texts;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Pętla agenta kopii zapasowej (bez interfejsu graficznego). Działa, dopóki nie dostanie polecenia STOP
 * (plik w {@code backup/commands/}) albo {@link #requestStop()} (np. Ctrl+C w konsoli).
 * <ul>
 *   <li>sprawdza folder źródłowy co {@code fast} (15 s), a po {@code idleAfter} (15 min) ciszy co {@code slow} (15 min),</li>
 *   <li>pojawienie się nowego pliku budzi agenta od razu (powiadomienia systemowe, {@link FolderWatcher}),</li>
 *   <li>po każdym sprawdzeniu i co {@code heartbeat} zapisuje stan do {@code backup/status.json},</li>
 *   <li>trzyma blokadę {@code backup/agent.lock} — drugi agent się nie uruchomi, a PhotoN wie, że agent żyje.</li>
 * </ul>
 */
public class AgentLoop {

    /** Agent już działa (blokada zajęta). */
    public static class AlreadyRunningException extends IOException {
        public AlreadyRunningException() {
            super("Agent kopii zapasowej już działa.");
        }
    }

    /** Opóźnienie po powiadomieniu — zbiera serię nowych plików w jedno sprawdzenie. */
    static final Duration EVENT_DEBOUNCE = Duration.ofMillis(1500);

    private enum Signal { FILE, COMMAND, CHECK, STOP }

    /** Odbiorca stanu i zdarzeń agenta (np. ikona w zasobniku systemowym). Wywoływany z wątku agenta. */
    public interface Observer {
        /** Nowy stan agenta (po każdym zapisie {@code status.json}). */
        void statusChanged(AgentStatus status);

        /** Zdarzenie warte powiadomienia (zarchiwizowane pliki, duplikaty do decyzji, błędy). */
        void notify(String title, String message, boolean warning);
    }

    private final String profileName;
    private final PhoneArchiveOptions options;
    private final AgentFiles files;
    private final HashCache cache;
    private final Duration heartbeat;
    private final Clock clock;
    private final PrintStream console;
    private final ArchiveWatcher watcher;
    private final CheckPacer pacer;
    private final BlockingQueue<Signal> signals = new LinkedBlockingQueue<>();

    private volatile boolean stopRequested;
    private volatile Observer observer;
    private FolderWatcher sourceWatcher;
    private FolderWatcher commandWatcher;
    private Instant startedAt;
    private Instant lastCheck;
    private Instant deadline;
    private boolean checking;
    private int archived;
    private int thumbnails;
    private int movedDuplicates;
    private int errors;
    private int waiting;
    private String lastProblem;

    /**
     * @param cache   pamięć podręczna sum plików archiwum (null = bez pamięci)
     * @param console gdzie dodatkowo wypisywać dziennik (konsola) albo null
     */
    public AgentLoop(String profileName, PhoneArchiveOptions options, AgentFiles files, HashCache cache,
                     Duration fast, Duration slow, Duration idleAfter, Duration quietPeriod, Duration heartbeat,
                     Clock clock, PrintStream console) {
        this.profileName = profileName;
        this.options = options;
        this.files = files;
        this.cache = cache;
        this.heartbeat = heartbeat;
        this.clock = clock;
        this.console = console;
        this.watcher = new ArchiveWatcher(profileName, options, cache, quietPeriod, clock);
        this.pacer = new CheckPacer(fast, slow, idleAfter, clock);
    }

    /** Domyślne tempo: 15 s / 15 min, plik gotowy po 30 s bez zmian, stan zapisywany co 30 s. */
    public static AgentLoop withDefaults(String profileName, PhoneArchiveOptions options, AgentFiles files,
                                         HashCache cache, PrintStream console) {
        return new AgentLoop(profileName, options, files, cache, CheckPacer.FAST, CheckPacer.SLOW,
                CheckPacer.IDLE_AFTER, Duration.ofSeconds(30), Duration.ofSeconds(30), Clock.systemDefaultZone(),
                console);
    }

    public void setObserver(Observer observer) {
        this.observer = observer;
    }

    /** Prosi o sprawdzenie folderu od razu (bezpieczne z dowolnego wątku). */
    public void requestCheckNow() {
        signals.offer(Signal.CHECK);
    }

    /** Prosi o zatrzymanie (bezpieczne z dowolnego wątku). */
    public void requestStop() {
        stopRequested = true;
        signals.offer(Signal.STOP);
    }

    /**
     * Uruchamia pętlę i blokuje do zatrzymania.
     *
     * @throws AlreadyRunningException gdy inny agent już działa
     */
    public void run() throws IOException {
        Files.createDirectories(files.commands());
        try (FileChannel lockChannel = FileChannel.open(files.lock(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try {
                lock = lockChannel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null;
            }
            if (lock == null) {
                throw new AlreadyRunningException();
            }
            try {
                loop();
            } finally {
                lock.release();
            }
        }
    }

    private void loop() {
        startedAt = clock.instant();
        BackupLog.rotateIfLarge(files.logFile());
        log("Agent uruchomiony (PID " + ProcessHandle.current().pid() + "): profil „" + profileName + "”, "
                + options.source() + " → " + options.target() + ". Sprawdzanie co " + describe(pacer.fast())
                + "; gdy przez " + describe(pacer.idleAfter()) + " nic się nie pojawi — co " + describe(pacer.slow()) + ".");
        try {
            commandWatcher = new FolderWatcher(files.commands(), false, null, () -> signals.offer(Signal.COMMAND));
        } catch (IOException e) {
            log("Polecenia będą odczytywane co " + describe(heartbeat) + " (brak powiadomień: " + e.getMessage() + ").");
        }
        processCommands();
        try {
            while (!stopRequested) {
                ensureSourceWatcher();
                runCheck();
                if (stopRequested) {
                    break;
                }
                waitForNextCheck();
            }
        } finally {
            closeWatchers();
            if (cache != null) {
                try {
                    cache.save();
                } catch (IOException e) {
                    System.err.println("Nie udało się zapisać pamięci podręcznej: " + e.getMessage());
                }
            }
            log("Agent zatrzymany.");
            writeStatus(AgentStatus.State.STOPPED);
        }
    }

    // =====================================================================
    // Sprawdzanie
    // =====================================================================

    private void runCheck() {
        checking = true;
        writeStatus(AgentStatus.State.RUNNING);
        ArchiveWatcher.CycleReport report;
        try {
            report = watcher.runCycle(() -> stopRequested);
        } catch (PhoneArchiver.CancelledException e) {
            checking = false;
            return;
        } catch (RuntimeException e) {
            report = new ArchiveWatcher.CycleReport(clock.instant(), null, null, List.of(),
                    "Nieoczekiwany błąd: " + e.getMessage(), 0);
        }
        checking = false;
        lastCheck = report.at();
        waiting = report.waiting();
        archived += report.archived();
        thumbnails += report.thumbnails();
        errors += report.errors().size();
        if (report.movedDuplicates() != null) {
            movedDuplicates += report.movedDuplicates().removed().size();
        }
        if (report.problem() != null) {
            if (!report.problem().equals(lastProblem)) {
                log(describe(report)); // ten sam problem nie jest powtarzany co 15 s
                notifyProblem(report.problem());
            }
            lastProblem = report.problem();
        } else {
            if (lastProblem != null) {
                log("Foldery znowu dostępne — archiwizacja wznowiona.");
            }
            lastProblem = null;
            if (report.eventful()) {
                log(describe(report));
                report.errors().stream().limit(10).forEach(e -> log("    błąd: " + e));
                notifyObserver(report);
            }
        }

        Duration next = pacer.afterCycle(report.activity());
        if (pacer.lastChange() == CheckPacer.Change.SLOWED_DOWN) {
            log("Brak nowych plików od " + describe(pacer.idleAfter()) + " — sprawdzanie co " + describe(pacer.slow())
                    + (sourceWatcher != null && sourceWatcher.isActive() ? " (nowe pliki i tak budzą agenta od razu)." : "."));
        } else if (pacer.lastChange() == CheckPacer.Change.SPED_UP) {
            log("Pojawiły się nowe pliki — sprawdzanie znowu co " + describe(pacer.fast()) + ".");
        }
        deadline = clock.instant().plus(next);
        writeStatus(AgentStatus.State.RUNNING);
    }

    /** Czeka do następnego sprawdzenia; budzą je polecenia i (przy dłuższym czekaniu) nowe pliki. */
    private void waitForNextCheck() {
        while (!stopRequested) {
            Instant now = clock.instant();
            Duration remaining = Duration.between(now, deadline);
            if (remaining.isNegative() || remaining.isZero()) {
                return;
            }
            Duration wait = remaining.compareTo(heartbeat) < 0 ? remaining : heartbeat;
            Signal signal;
            try {
                signal = signals.poll(Math.max(1, wait.toMillis()), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopRequested = true;
                return;
            }
            if (signal == null) {
                processCommands(); // na wypadek, gdyby powiadomienie o poleceniu nie dotarło
                writeStatus(AgentStatus.State.RUNNING);
                continue;
            }
            switch (signal) {
                case STOP -> stopRequested = true;
                case COMMAND -> processCommands();
                case CHECK -> {
                    log("Sprawdzanie na żądanie (ikona w zasobniku).");
                    deadline = clock.instant();
                }
                case FILE -> {
                    // Nowy plik: sprawdzamy za chwilę (zbieramy serię plików), nie czekając na pełny odstęp.
                    Instant soon = clock.instant().plus(EVENT_DEBOUNCE);
                    if (deadline.isAfter(soon)) {
                        deadline = soon;
                        writeStatus(AgentStatus.State.RUNNING);
                    }
                }
                default -> { }
            }
        }
    }

    private void ensureSourceWatcher() {
        if (sourceWatcher != null && sourceWatcher.isActive()) {
            return;
        }
        boolean hadWatcher = sourceWatcher != null;
        if (sourceWatcher != null) {
            sourceWatcher.close();
            sourceWatcher = null;
        }
        if (!Files.isDirectory(options.source())) {
            return;
        }
        try {
            sourceWatcher = new FolderWatcher(options.source(), options.includeSubfolders(), options.target(),
                    () -> signals.offer(Signal.FILE));
            if (hadWatcher) {
                log("Powiadomienia o nowych plikach wznowione.");
            }
        } catch (IOException e) {
            log("Natychmiastowe powiadomienia niedostępne (" + e.getMessage() + ") — tylko sprawdzanie cykliczne.");
        }
    }

    private void closeWatchers() {
        if (sourceWatcher != null) {
            sourceWatcher.close();
        }
        if (commandWatcher != null) {
            commandWatcher.close();
        }
    }

    // =====================================================================
    // Polecenia
    // =====================================================================

    private void processCommands() {
        List<Path> commandFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(files.commands(), "*.json")) {
            stream.forEach(commandFiles::add);
        } catch (IOException e) {
            return;
        }
        commandFiles.sort(null);
        for (Path file : commandFiles) {
            AgentCommand command;
            try {
                command = Json.mapper().readValue(file.toFile(), AgentCommand.class);
            } catch (IOException e) {
                // plik mógł być jeszcze zapisywany — spróbujemy przy następnej okazji, chyba że jest stary
                if (isOld(file)) {
                    deleteQuietly(file);
                }
                continue;
            }
            deleteQuietly(file);
            if (command.type() == null || command.createdAt() != null && command.createdAt().isBefore(startedAt)) {
                continue; // polecenie sprzed startu agenta
            }
            execute(command);
        }
    }

    private void execute(AgentCommand command) {
        switch (command.type()) {
            case STOP -> {
                log("Otrzymano polecenie zatrzymania.");
                stopRequested = true;
            }
            case CHECK_NOW -> {
                log("Otrzymano polecenie „Sprawdź teraz”.");
                deadline = clock.instant();
            }
            case REMOVE_DUPLICATES -> {
                Set<Path> requested = new HashSet<>(command.paths());
                List<PhoneArchiver.Duplicate> chosen = watcher.pendingDuplicates().stream()
                        .filter(d -> requested.contains(d.source())).toList();
                boolean toTrash = command.toTrash() && PhoneArchiver.trashSupported();
                PhoneArchiver.RemovalOutcome outcome = watcher.removePending(chosen, toTrash);
                log((toTrash ? "Przeniesiono do kosza " : "Usunięto ")
                        + Texts.count(outcome.removed().size(), "zgłoszony duplikat", "zgłoszone duplikaty",
                        "zgłoszonych duplikatów") + "."
                        + (outcome.skippedProtected() > 0
                        ? " Pominięto w chronionych folderach „__”: " + outcome.skippedProtected() + "." : ""));
                outcome.errors().stream().limit(10).forEach(e -> log("    błąd: " + e));
                errors += outcome.errors().size();
                writeStatus(AgentStatus.State.RUNNING);
            }
            default -> { }
        }
    }

    private boolean isOld(Path file) {
        try {
            return Duration.between(Files.getLastModifiedTime(file).toInstant(), Instant.now()).toSeconds() > 60;
        } catch (IOException e) {
            return true;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // ignorujemy
        }
    }

    // =====================================================================
    // Stan i dziennik
    // =====================================================================

    private void writeStatus(AgentStatus.State state) {
        List<AgentStatus.PendingDuplicate> pending = watcher.pendingDuplicates().stream()
                .map(d -> new AgentStatus.PendingDuplicate(d.source(), d.existing(), d.size())).toList();
        Instant now = clock.instant();
        boolean running = state == AgentStatus.State.RUNNING;
        AgentStatus status = new AgentStatus(state, ProcessHandle.current().pid(), profileName, options.source(),
                options.target(), startedAt, now, lastCheck, running ? deadline : null,
                pacer.currentInterval().toSeconds(), pacer.isSlow(),
                running && sourceWatcher != null && sourceWatcher.isActive(), running && checking,
                archived, thumbnails, movedDuplicates, errors, waiting, pending, lastProblem,
                running ? null : now);
        Observer current = observer;
        if (current != null) {
            try {
                current.statusChanged(status);
            } catch (RuntimeException e) {
                System.err.println("Błąd ikony w zasobniku: " + e.getMessage());
            }
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Json.writeAtomically(files.status(), status);
                return;
            } catch (IOException e) {
                // PhotoN mógł akurat czytać plik — ponawiamy za chwilę
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Powiadomienie tylko o tym, co warte uwagi: zarchiwizowane pliki, nowe duplikaty do decyzji, błędy. */
    private void notifyObserver(ArchiveWatcher.CycleReport report) {
        Observer current = observer;
        if (current == null) {
            return;
        }
        List<String> parts = new ArrayList<>();
        if (report.archived() > 0) {
            parts.add("Zarchiwizowano " + Texts.count(report.archived(), "plik", "pliki", "plików")
                    + " do " + report.summary().dayFolder().getFileName() + ".");
        }
        if (report.movedDuplicates() != null && !report.movedDuplicates().removed().isEmpty()) {
            parts.add("Duplikaty przeniesione do śmietnika: " + report.movedDuplicates().removed().size() + ".");
        }
        if (!report.newDuplicates().isEmpty()) {
            parts.add("Nowe duplikaty czekają na decyzję: " + report.newDuplicates().size() + ".");
        }
        if (!report.errors().isEmpty()) {
            parts.add("Błędy: " + report.errors().size() + " — szczegóły w dzienniku.");
        }
        if (parts.isEmpty()) {
            return; // np. tylko pliki w trakcie przesyłania
        }
        try {
            current.notify("PhotoN — kopia zapasowa", String.join("\n", parts), !report.errors().isEmpty());
        } catch (RuntimeException e) {
            System.err.println("Błąd powiadomienia: " + e.getMessage());
        }
    }

    /** Ostrzeżenie o nowym problemie (np. niedostępny dysk) — raz, nie co każde sprawdzenie. */
    private void notifyProblem(String problem) {
        Observer current = observer;
        if (current == null) {
            return;
        }
        try {
            current.notify("PhotoN — kopia zapasowa wstrzymana", problem, true);
        } catch (RuntimeException e) {
            System.err.println("Błąd powiadomienia: " + e.getMessage());
        }
    }

    private void log(String text) {
        Instant now = clock.instant();
        BackupLog.append(files.logFile(), now, text);
        if (console != null) {
            console.println(text);
        }
    }

    static String describe(ArchiveWatcher.CycleReport report) {
        if (report.problem() != null) {
            return "Pominięto sprawdzenie: " + report.problem();
        }
        List<String> parts = new ArrayList<>();
        if (report.archived() > 0) {
            PhoneArchiver.Summary s = report.summary();
            parts.add("zarchiwizowano " + Texts.count(report.archived(), "plik", "pliki", "plików")
                    + " (" + Texts.formatBytes(s.archivedBytes()) + ") do " + s.dayFolder().getFileName());
        }
        if (report.thumbnails() > 0) {
            parts.add("miniatury: " + report.thumbnails());
        }
        if (report.movedDuplicates() != null && !report.movedDuplicates().removed().isEmpty()) {
            parts.add("przeniesiono duplikaty do śmietnika: " + report.movedDuplicates().removed().size());
        }
        if (!report.newDuplicates().isEmpty()) {
            parts.add("nowe duplikaty do decyzji: " + report.newDuplicates().size());
        }
        if (report.waiting() > 0) {
            parts.add("w trakcie przesyłania: " + report.waiting());
        }
        if (!report.errors().isEmpty()) {
            parts.add("błędy: " + report.errors().size());
        }
        String text = String.join(", ", parts);
        return text.isEmpty() ? "Bez zmian." : Character.toUpperCase(text.charAt(0)) + text.substring(1) + ".";
    }

    static String describe(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds > 0 && seconds % 60 == 0) {
            long minutes = seconds / 60;
            return minutes == 1 ? "minutę" : Texts.count((int) minutes, "minutę", "minuty", "minut");
        }
        if (seconds == 0) {
            return duration.toMillis() + " ms";
        }
        return Texts.count((int) seconds, "sekundę", "sekundy", "sekund");
    }
}
