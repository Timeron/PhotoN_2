package com.photon.backup;

import com.photon.CancelledException;
import com.photon.duplicates.scan.HashCache;
import com.photon.phonearchive.ArchiveIndex;
import com.photon.phonearchive.PhoneArchiver;
import com.photon.remote.Locations;
import com.photon.ui.Texts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Jeden profil obsługiwany przez agenta: własny folder źródłowy i archiwum, obserwowanie folderu, tempo
 * sprawdzania, sesje, liczniki i stan. Agent ({@link AgentLoop}) prowadzi kilka takich kanałów w jednym wątku.
 */
final class ProfileChannel {

    private final AgentProfile profile;
    private final AgentJournal journal;
    private final Clock clock;
    private final ArchiveWatcher watcher;
    private final CheckPacer pacer;
    private final ActivityTracker activity;
    private final Runnable onNewFile;
    private CheckScheduler scheduler;

    private FolderWatcher sourceWatcher;
    private Instant lastCheck;
    /** Termin następnego sprawdzenia (null = tylko powiadomienia). */
    private Instant deadline;
    private boolean checking;
    private int archived;
    private int thumbnails;
    private int movedDuplicates;
    private int errors;
    private int waiting;
    private String lastProblem;

    /**
     * @param index     spis archiwum (wspólny dla profili z tym samym archiwum)
     * @param onNewFile wywoływane z wątku obserwatora, gdy w folderze źródłowym pojawi się plik
     */
    ProfileChannel(AgentProfile profile, AgentJournal journal, HashCache cache, Duration quietPeriod,
                   CheckPacer pacer, Clock clock, ArchiveIndex index, Path sessionsFolder, Runnable onNewFile) {
        this.profile = profile;
        this.journal = journal;
        this.clock = clock;
        this.pacer = pacer;
        this.onNewFile = onNewFile;
        this.watcher = new ArchiveWatcher(profile.name(), profile.options(), cache, quietPeriod, clock, index);
        this.activity = new ActivityTracker(profile.name(), journal, sessionsFolder);
    }

    String name() {
        return profile.name();
    }

    AgentJournal journal() {
        return journal;
    }

    void setSessionEnd(Duration duration) {
        activity.setSessionEnd(duration);
    }

    /** Przygotowuje kanał do pracy: pierwsze sprawdzenie od razu. */
    void start(Duration safetyCheck) {
        scheduler = new CheckScheduler(pacer, safetyCheck);
        deadline = clock.instant();
        journal.log("Profil „" + profile.name() + "”: " + profile.options().source() + " → " + profile.options().target()
                + ". " + scheduler.startupDescription());
        if (Locations.isRemote(profile.options().source())) {
            journal.log("Folder do archiwizacji jest na serwerze " + Locations.serverName(profile.options().source())
                    + " — serwer nie powiadamia o nowych plikach, więc działa tylko sprawdzanie cykliczne.");
        }
    }

    // =====================================================================
    // Terminy
    // =====================================================================

    Instant deadline() {
        return deadline;
    }

    boolean isDue(Instant now) {
        return deadline != null && !deadline.isAfter(now);
    }

    /** Sprawdzenie od razu (polecenie „Sprawdź teraz”). */
    void checkNow() {
        deadline = clock.instant();
    }

    /** Nowy plik: sprawdzenie za chwilę (seria plików w jednym sprawdzeniu), nie czekając na pełny odstęp. */
    void checkSoon(Duration delay) {
        Instant soon = clock.instant().plus(delay);
        if (deadline == null || deadline.isAfter(soon)) {
            deadline = soon;
        }
    }

    // =====================================================================
    // Sprawdzanie
    // =====================================================================

    /**
     * Jedno sprawdzenie folderu.
     *
     * @param statusChanged wywoływane na początku (stan „sprawdzanie”)
     * @return false, gdy sprawdzenie przerwano (zatrzymanie agenta)
     */
    boolean check(BooleanSupplier stopRequested, Runnable statusChanged) {
        ensureSourceWatcher();
        checking = true;
        statusChanged.run();
        ArchiveWatcher.CycleReport report;
        try {
            report = watcher.runCycle(stopRequested);
        } catch (CancelledException e) {
            checking = false;
            return false;
        } catch (RuntimeException e) {
            report = new ArchiveWatcher.CycleReport(clock.instant(), null, null, List.of(),
                    "Nieoczekiwany błąd: " + e.getMessage(), 0, List.of());
        }
        checking = false;
        countAndReport(report);
        Instant now = clock.instant();
        activity.update(report, now);
        CheckScheduler.Plan plan = scheduler.afterCheck(report, watcherActive(), activity.sessionEndsAt(), now);
        if (plan.logMessage() != null) {
            journal.log(plan.logMessage());
        }
        deadline = plan.deadline();
        return true;
    }

    /** Liczniki od uruchomienia, dziennik i powiadomienia o przebiegu. */
    private void countAndReport(ArchiveWatcher.CycleReport report) {
        lastCheck = report.at();
        waiting = report.waiting();
        archived += report.archived();
        thumbnails += report.thumbnails();
        errors += report.errors().size();
        if (report.movedDuplicates() != null) {
            movedDuplicates += report.movedDuplicates().removed().size();
        }
        if (report.problem() != null) {
            if (!report.problem().equals(lastProblem)) { // ten sam problem nie jest powtarzany co sprawdzenie
                journal.log(AgentTexts.describe(report));
                journal.notify("PhotoN — kopia zapasowa wstrzymana", report.problem(), true);
            }
            lastProblem = report.problem();
            return;
        }
        if (lastProblem != null) {
            journal.log("Foldery znowu dostępne — archiwizacja wznowiona.");
        }
        lastProblem = null;
        if (report.eventful()) {
            journal.log(AgentTexts.describe(report));
            report.errors().stream().limit(10).forEach(e -> journal.log("    błąd: " + e));
            String notification = AgentTexts.notification(report);
            if (notification != null) {
                journal.notify("PhotoN — kopia zapasowa", notification, !report.errors().isEmpty());
            }
        }
    }

    // =====================================================================
    // Obserwowanie folderu
    // =====================================================================

    private boolean watcherActive() {
        return sourceWatcher != null && sourceWatcher.isActive();
    }

    private void ensureSourceWatcher() {
        if (watcherActive() || Locations.isRemote(profile.options().source())) {
            return; // działa albo serwer zdalny (tylko sprawdzanie cykliczne)
        }
        boolean hadWatcher = sourceWatcher != null;
        if (sourceWatcher != null) {
            sourceWatcher.close();
            sourceWatcher = null;
        }
        if (!Files.isDirectory(profile.options().source())) {
            return;
        }
        try {
            sourceWatcher = new FolderWatcher(profile.options().source(), profile.options().includeSubfolders(),
                    profile.options().target(), onNewFile);
            if (hadWatcher) {
                journal.log("Powiadomienia o nowych plikach wznowione.");
            }
        } catch (IOException e) {
            journal.log("Natychmiastowe powiadomienia niedostępne (" + e.getMessage() + ") — tylko sprawdzanie cykliczne.");
        }
    }

    /** Zatrzymanie agenta: obserwowanie folderu i podsumowanie trwającej sesji. */
    void close() {
        if (sourceWatcher != null) {
            sourceWatcher.close();
        }
        activity.finishSession();
    }

    // =====================================================================
    // Duplikaty do decyzji
    // =====================================================================

    /** Usuwa zgłoszone duplikaty tego profilu wskazane w poleceniu. @return liczba błędów */
    int removeDuplicates(Set<Path> requested, boolean toTrash) {
        List<PhoneArchiver.Duplicate> chosen = watcher.pendingDuplicates().stream()
                .filter(d -> requested.contains(d.source())).toList();
        if (chosen.isEmpty()) {
            return 0;
        }
        PhoneArchiver.RemovalOutcome outcome = watcher.removePending(chosen, toTrash);
        journal.log((toTrash ? "Przeniesiono do kosza " : "Usunięto ")
                + Texts.count(outcome.removed().size(), "zgłoszony duplikat", "zgłoszone duplikaty",
                "zgłoszonych duplikatów") + "."
                + (outcome.skippedProtected() > 0
                ? " Pominięto w chronionych folderach „__”: " + outcome.skippedProtected() + "." : ""));
        outcome.errors().stream().limit(10).forEach(e -> journal.log("    błąd: " + e));
        errors += outcome.errors().size();
        return outcome.errors().size();
    }

    // =====================================================================
    // Stan
    // =====================================================================

    AgentStatus.ProfileStatus status(boolean running) {
        List<AgentStatus.PendingDuplicate> pending = watcher.pendingDuplicates().stream()
                .map(d -> new AgentStatus.PendingDuplicate(d.source(), d.existing(), d.size())).toList();
        boolean waitingForEvents = running && scheduler != null && scheduler.eventDriven() && !checking;
        AgentStatus.Schedule schedule = new AgentStatus.Schedule(lastCheck, running ? deadline : null,
                scheduler == null ? 0 : scheduler.intervalSeconds(waitingForEvents),
                !waitingForEvents && scheduler != null && scheduler.slow(),
                running && watcherActive(), running && checking, waitingForEvents);
        return new AgentStatus.ProfileStatus(profile.name(), profile.options().source(), profile.options().target(),
                schedule, archived, thumbnails, movedDuplicates, errors, waiting, pending, lastProblem,
                activity.snapshot(waiting), activity.lastSessionSummary(), activity.stalled());
    }
}
