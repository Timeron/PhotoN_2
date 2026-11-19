package com.photon.backup;

import com.photon.duplicates.scan.HashCache;
import com.photon.phonearchive.ArchiveIndex;
import com.photon.phonearchive.PhoneArchiveOptions;
import com.photon.phonearchive.SourceDuplicates;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Pętla agenta kopii zapasowej (bez interfejsu graficznego). Obsługuje jeden albo kilka profili naraz
 * ({@link ProfileChannel}) — każdy ze swoim folderem źródłowym i archiwum — w jednym wątku: profile są
 * sprawdzane po kolei, każdy we własnym terminie. Działa, dopóki nie dostanie polecenia STOP
 * (plik w {@code backup/commands/}) albo {@link #requestStop()} (np. Ctrl+C w konsoli).
 * <ul>
 *   <li>kiedy sprawdzać — {@link CheckScheduler} każdego profilu (tryb zdarzeń przy działających
 *       powiadomieniach, inaczej sprawdzanie cykliczne),</li>
 *   <li>stan w {@code backup/status.json} — {@link StatusFile} (tylko przy zmianach),</li>
 *   <li>dziennik i powiadomienia — {@link AgentJournal} (przy kilku profilach z nazwą profilu),</li>
 *   <li>blokada {@code backup/agent.lock} — drugi agent się nie uruchomi, a PhotoN wie, że agent żyje.</li>
 * </ul>
 */
public class AgentLoop {

    /** Agent już działa (blokada zajęta). */
    public static class AlreadyRunningException extends IOException {
        public AlreadyRunningException() {
            super("Agent kopii zapasowej już działa.");
        }
    }

    /** Odbiorca stanu i zdarzeń agenta (np. ikona w zasobniku systemowym). Wywoływany z wątku agenta. */
    public interface Observer {
        /** Nowy stan agenta (po każdym zapisie {@code status.json}). */
        void statusChanged(AgentStatus status);

        /** Zdarzenie warte powiadomienia (zarchiwizowane pliki, duplikaty do decyzji, błędy). */
        void notify(String title, String message, boolean warning);
    }

    /** Opóźnienie po powiadomieniu — zbiera serię nowych plików w jedno sprawdzenie. */
    static final Duration EVENT_DEBOUNCE = Duration.ofMillis(1500);

    private enum Kind { FILE, COMMAND, CHECK, STOP }

    /** Sygnał budzący pętlę; przy {@code FILE} — profil, w którego folderze pojawił się plik. */
    private record Signal(Kind kind, ProfileChannel channel) {
    }

    private final AgentFiles files;
    private final HashCache cache;
    /** Co ile zaglądać do folderu poleceń, gdy powiadomienia o poleceniach nie działają. */
    private final Duration commandPoll;
    private final Clock clock;
    private final AgentJournal journal;
    private final StatusFile statusFile;
    private final CommandInbox inbox;
    private final List<ProfileChannel> channels = new ArrayList<>();
    private final BlockingQueue<Signal> signals = new LinkedBlockingQueue<>();
    /** Kontrolne sprawdzenie przy działających powiadomieniach (null = bez kontrolnego sprawdzania). */
    private Duration safetyCheck = Duration.ofHours(1);

    private volatile boolean stopRequested;
    private FolderWatcher commandWatcher;
    private Instant startedAt;

    /** Agent dla jednego profilu. */
    public AgentLoop(String profileName, PhoneArchiveOptions options, AgentFiles files, HashCache cache,
                     Duration fast, Duration slow, Duration idleAfter, Duration quietPeriod, Duration commandPoll,
                     Clock clock, PrintStream console) {
        this(List.of(new AgentProfile(profileName, options)), files, cache, fast, slow, idleAfter, quietPeriod,
                commandPoll, clock, console);
    }

    /**
     * Agent dla kilku profili (każdy ze swoim tempem sprawdzania, sesjami i stanem).
     *
     * @param cache   pamięć podręczna sum plików archiwum (null = bez pamięci)
     * @param console gdzie dodatkowo wypisywać dziennik (konsola) albo null
     */
    public AgentLoop(List<AgentProfile> profiles, AgentFiles files, HashCache cache,
                     Duration fast, Duration slow, Duration idleAfter, Duration quietPeriod, Duration commandPoll,
                     Clock clock, PrintStream console) {
        this.files = files;
        this.cache = cache;
        this.commandPoll = commandPoll;
        this.clock = clock;
        this.journal = new AgentJournal(files, clock, console);
        this.statusFile = new StatusFile(files.status());
        this.inbox = new CommandInbox(files.commands());
        Path sessions = files.logFile().toAbsolutePath().getParent().resolve("sesje");
        // Profile z tym samym archiwum mają wspólny spis — plik dodany przez jeden jest duplikatem dla drugiego.
        Map<Path, ArchiveIndex> indexes = new HashMap<>();
        boolean prefix = profiles.size() > 1;
        for (AgentProfile profile : profiles) {
            ArchiveIndex index = indexes.computeIfAbsent(profile.options().target().toAbsolutePath().normalize(),
                    t -> new ArchiveIndex(ArchiveWatcher.INDEX_MAX_AGE, clock));
            ProfileChannel[] self = new ProfileChannel[1];
            self[0] = new ProfileChannel(profile, journal.forProfile(profile.name(), prefix), cache, quietPeriod,
                    new CheckPacer(fast, slow, idleAfter, clock), clock, index, sessions,
                    () -> signals.offer(new Signal(Kind.FILE, self[0])));
            channels.add(self[0]);
        }
    }

    /**
     * Domyślne tempo: 15 s; plik przesłany, gdy bez zmian przez czas z {@link AgentSettings}; po 15 min ciszy
     * (bez powiadomień) i kontrolnie (z powiadomieniami) według {@link AgentSettings}.
     */
    public static AgentLoop withDefaults(List<AgentProfile> profiles, AgentFiles files, HashCache cache,
                                         AgentSettings settings, PrintStream console) {
        return new AgentLoop(profiles, files, cache, CheckPacer.FAST, settings.slowPolling(), CheckPacer.IDLE_AFTER,
                settings.quietPeriod(), Duration.ofSeconds(30), Clock.systemDefaultZone(), console)
                .withSafetyCheck(settings.safetyCheck());
    }

    /** Po jakim czasie bez aktywności sesja się kończy (testy). */
    AgentLoop withSessionEnd(Duration duration) {
        channels.forEach(c -> c.setSessionEnd(duration));
        return this;
    }

    /** Kontrolne sprawdzenie przy działających powiadomieniach (null = wyłączone). */
    public AgentLoop withSafetyCheck(Duration interval) {
        this.safetyCheck = interval;
        return this;
    }

    public void setObserver(Observer observer) {
        journal.setObserver(observer);
    }

    /** Prosi o sprawdzenie folderów od razu (bezpieczne z dowolnego wątku). */
    public void requestCheckNow() {
        signals.offer(new Signal(Kind.CHECK, null));
    }

    /** Prosi o zatrzymanie (bezpieczne z dowolnego wątku). */
    public void requestStop() {
        stopRequested = true;
        signals.offer(new Signal(Kind.STOP, null));
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
        journal.log("Agent uruchomiony (PID " + ProcessHandle.current().pid() + "), "
                + (channels.size() == 1 ? "profil „" + channels.get(0).name() + "”."
                : "profile: " + String.join(", ", channels.stream().map(ProfileChannel::name).toList()) + "."));
        channels.forEach(c -> c.start(safetyCheck));
        try {
            commandWatcher = new FolderWatcher(files.commands(), false, null,
                    () -> signals.offer(new Signal(Kind.COMMAND, null)));
        } catch (IOException e) {
            journal.log("Polecenia będą odczytywane co " + AgentTexts.describe(commandPoll) + " (brak powiadomień: "
                    + e.getMessage() + ").");
        }
        processCommands();
        try {
            while (!stopRequested) {
                checkDueProfiles();
                if (stopRequested) {
                    break;
                }
                waitForNextCheck();
            }
        } finally {
            if (commandWatcher != null) {
                commandWatcher.close();
            }
            channels.forEach(ProfileChannel::close); // także podsumowanie trwających sesji
            saveCache();
            journal.log("Agent zatrzymany.");
            writeStatus(AgentStatus.State.STOPPED);
        }
    }

    /** Sprawdza po kolei profile, których termin minął. */
    private void checkDueProfiles() {
        for (ProfileChannel channel : channels) {
            if (stopRequested) {
                return;
            }
            if (channel.isDue(clock.instant())
                    && channel.check(() -> stopRequested, () -> writeStatus(AgentStatus.State.RUNNING))) {
                writeStatus(AgentStatus.State.RUNNING);
            }
        }
    }

    /**
     * Czeka do najbliższego terminu któregoś profilu (albo — bez terminów — do powiadomienia). Budzą je
     * polecenia i nowe pliki. Nie zapisuje niczego na dysk, dopóki nic się nie dzieje (dyski mogą zasnąć).
     */
    private void waitForNextCheck() {
        while (!stopRequested) {
            Instant earliest = channels.stream().map(ProfileChannel::deadline).filter(Objects::nonNull)
                    .min(Instant::compareTo).orElse(null);
            Duration wait = null;
            if (earliest != null) {
                wait = Duration.between(clock.instant(), earliest);
                if (wait.isNegative() || wait.isZero()) {
                    return;
                }
            }
            // Bez powiadomień o poleceniach trzeba co jakiś czas zajrzeć do folderu poleceń.
            boolean pollCommands = commandWatcher == null || !commandWatcher.isActive();
            if (pollCommands) {
                wait = shorter(wait, commandPoll);
            }
            if (statusFile.pending()) {
                wait = shorter(wait, StatusFile.RETRY);
            }
            Signal signal;
            try {
                signal = wait == null ? signals.take() : signals.poll(Math.max(1, wait.toMillis()), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopRequested = true;
                return;
            }
            if (signal == null) {
                if (pollCommands) {
                    processCommands();
                }
                statusFile.retry();
                continue;
            }
            switch (signal.kind()) {
                case STOP -> stopRequested = true;
                case COMMAND -> processCommands();
                case CHECK -> {
                    journal.log("Sprawdzanie na żądanie (ikona w zasobniku).");
                    channels.forEach(ProfileChannel::checkNow);
                }
                // Nowy plik (albo koniec obserwacji folderu) — tylko ten profil, za chwilę.
                case FILE -> signal.channel().checkSoon(EVENT_DEBOUNCE);
                default -> { }
            }
        }
    }

    private static Duration shorter(Duration current, Duration limit) {
        return current == null || current.compareTo(limit) > 0 ? limit : current;
    }

    private void saveCache() {
        if (cache != null) {
            try {
                cache.save();
            } catch (IOException e) {
                System.err.println("Nie udało się zapisać pamięci podręcznej: " + e.getMessage());
            }
        }
    }

    // =====================================================================
    // Polecenia
    // =====================================================================

    private void processCommands() {
        for (AgentCommand command : inbox.take(startedAt)) {
            execute(command);
        }
    }

    private void execute(AgentCommand command) {
        switch (command.type()) {
            case STOP -> {
                journal.log("Otrzymano polecenie zatrzymania.");
                stopRequested = true;
            }
            case CHECK_NOW -> {
                journal.log("Otrzymano polecenie „Sprawdź teraz”.");
                channels.forEach(ProfileChannel::checkNow);
            }
            case REMOVE_DUPLICATES -> {
                Set<Path> requested = new HashSet<>(command.paths());
                boolean toTrash = command.toTrash() && SourceDuplicates.trashSupported();
                channels.forEach(c -> c.removeDuplicates(requested, toTrash));
                writeStatus(AgentStatus.State.RUNNING);
            }
            default -> { }
        }
    }

    // =====================================================================
    // Stan
    // =====================================================================

    private void writeStatus(AgentStatus.State state) {
        boolean running = state == AgentStatus.State.RUNNING;
        AgentStatus status = new AgentStatus(state, ProcessHandle.current().pid(), startedAt,
                running ? null : clock.instant(), channels.stream().map(c -> c.status(running)).toList());
        journal.statusChanged(status);
        statusFile.write(status);
    }
}
