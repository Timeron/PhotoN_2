package com.photon.backup;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Śledzi aktywność między sprawdzeniami: sesje (początek, postęp, podsumowanie z listą plików w CSV)
 * i pliki, których przesyłanie stoi (zgłaszane raz).
 */
final class ActivityTracker {

    /** Sesja kończy się po takim czasie bez nowych plików (i bez plików w trakcie przesyłania). */
    static final Duration SESSION_END = Duration.ofMinutes(15);

    private final AgentJournal journal;
    private final Path sessionsFolder;
    private final String profile;
    private Duration sessionEnd = SESSION_END;
    private BackupSession session;
    private String lastSessionSummary;
    private List<Path> stalled = List.of();
    private final Set<Path> reportedStalled = new HashSet<>();

    ActivityTracker(String profile, AgentJournal journal, Path sessionsFolder) {
        this.profile = profile;
        this.journal = journal;
        this.sessionsFolder = sessionsFolder;
    }

    void setSessionEnd(Duration duration) {
        this.sessionEnd = duration;
    }

    /** Uwzględnia wynik sprawdzenia (przebieg pominięty z powodu problemu niczego nie zmienia). */
    void update(ArchiveWatcher.CycleReport report, Instant now) {
        if (report.summary() == null) {
            return;
        }
        updateSession(report, now);
        updateStalled(report);
    }

    private void updateSession(ArchiveWatcher.CycleReport report, Instant now) {
        int uploading = report.waiting() - report.stalled().size(); // stojące przesyłanie nie przedłuża sesji
        if (session == null && (report.activity() || uploading > 0)) {
            session = new BackupSession(now);
            journal.log("Początek sesji — pojawiły się nowe pliki.");
        }
        if (session == null) {
            return;
        }
        session.add(report, now);
        if (session.finished(now, sessionEnd, uploading)) {
            finishSession();
        }
    }

    /** Kończy trwającą sesję: podsumowanie w dzienniku i powiadomieniu, lista plików w CSV. */
    void finishSession() {
        if (session == null) {
            return;
        }
        String summary = session.summary();
        String csvInfo;
        try {
            csvInfo = " Lista plików: " + session.writeCsv(sessionsFolder, profile);
        } catch (IOException e) {
            csvInfo = " (nie udało się zapisać listy plików: " + e.getMessage() + ")";
        }
        journal.log(summary + csvInfo);
        journal.notify("PhotoN — sesja zakończona", summary, false);
        lastSessionSummary = summary;
        session = null;
    }

    private void updateStalled(ArchiveWatcher.CycleReport report) {
        stalled = report.stalled();
        List<Path> fresh = stalled.stream().filter(f -> !reportedStalled.contains(f)).toList();
        reportedStalled.retainAll(stalled);
        if (!fresh.isEmpty()) {
            reportedStalled.addAll(fresh);
            journal.logAndNotify("PhotoN — przesyłanie stoi", AgentTexts.stalled(fresh), true);
        }
    }

    /** Kiedy kończy się trwająca sesja (null = brak sesji). */
    Instant sessionEndsAt() {
        return session == null ? null : session.endsAt(sessionEnd);
    }

    AgentStatus.Session snapshot(int waiting) {
        return session == null ? null : session.snapshot(waiting);
    }

    String lastSessionSummary() {
        return lastSessionSummary;
    }

    List<Path> stalled() {
        return stalled;
    }
}
