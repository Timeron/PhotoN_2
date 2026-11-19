package com.photon.backup;

import com.photon.phonearchive.PhoneArchiver;
import com.photon.ui.Texts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Sesja kopii zapasowej: od pierwszego nowego pliku do {@code endAfter} bez żadnej aktywności (nic nie
 * przybywa, nic nie czeka na przesłanie). Zbiera postęp (do podglądu w trakcie) i na końcu daje podsumowanie
 * oraz listę plików w CSV — przydatne przy rzadkich, dużych paczkach zdjęć z telefonu.
 */
final class BackupSession {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")
            .withZone(ZoneId.systemDefault());

    private final Instant startedAt;
    private Instant lastActivity;
    private int archived;
    private long bytes;
    private int thumbnails;
    private int duplicates;
    private int errors;
    private final List<String> rows = new ArrayList<>();

    BackupSession(Instant startedAt) {
        this.startedAt = startedAt;
        this.lastActivity = startedAt;
    }

    /** Dolicza wynik sprawdzenia. */
    void add(ArchiveWatcher.CycleReport report, Instant now) {
        if (report.activity() || report.waiting() > 0) {
            lastActivity = now;
        }
        PhoneArchiver.Summary summary = report.summary();
        if (summary == null) {
            return;
        }
        archived += report.archived();
        bytes += summary.archivedBytes();
        thumbnails += report.thumbnails();
        errors += report.errors().size();
        for (Path file : summary.moved()) {
            rows.add(row(now, "zarchiwizowano", file, null, size(file)));
        }
        for (Path file : summary.copied()) {
            rows.add(row(now, "skopiowano (folder chroniony)", file, null, size(file)));
        }
        if (report.movedDuplicates() != null) {
            duplicates += report.movedDuplicates().removed().size();
            for (Path file : report.movedDuplicates().removed()) {
                rows.add(row(now, "duplikat → śmietnik", file, null, -1));
            }
        }
        duplicates += report.newDuplicates().size();
        for (PhoneArchiver.Duplicate d : report.newDuplicates()) {
            rows.add(row(now, "duplikat do decyzji", d.source(), d.existing(), d.size()));
        }
        for (String error : report.errors()) {
            rows.add(TIME.format(now) + ";błąd;" + csv(error) + ";;");
        }
    }

    /** Czy sesja się skończyła: od ostatniej aktywności minęło {@code endAfter} i nic nie czeka. */
    boolean finished(Instant now, Duration endAfter, int waiting) {
        return waiting == 0 && !now.isBefore(lastActivity.plus(endAfter));
    }

    Instant endsAt(Duration endAfter) {
        return lastActivity.plus(endAfter);
    }

    AgentStatus.Session snapshot(int waiting) {
        return new AgentStatus.Session(startedAt, lastActivity, archived, bytes, thumbnails, duplicates, errors, waiting);
    }

    /** Podsumowanie po zakończeniu (do dziennika i powiadomienia). */
    String summary() {
        Duration length = Duration.between(startedAt, lastActivity);
        return "Sesja " + TIME.format(startedAt) + " – " + TIME.format(lastActivity).substring(11)
                + " (" + describe(length) + "): zarchiwizowano " + Texts.count(archived, "plik", "pliki", "plików")
                + " (" + Texts.formatBytes(bytes) + ")" + (thumbnails > 0 ? ", miniatury: " + thumbnails : "")
                + ", duplikaty: " + duplicates + ", błędy: " + errors + ".";
    }

    /** Zapisuje listę plików sesji (CSV, średnik, UTF-8 z BOM — otwiera się poprawnie w Excelu). */
    Path writeCsv(Path folder) throws IOException {
        Files.createDirectories(folder);
        Path file = folder.resolve("sesja-" + FILE_TIME.format(startedAt) + ".csv");
        List<String> lines = new ArrayList<>();
        lines.add("﻿czas;operacja;plik;w archiwum;rozmiar (B)");
        lines.addAll(rows);
        Files.write(file, lines, StandardCharsets.UTF_8);
        return file;
    }

    int archived() {
        return archived;
    }

    private static String row(Instant at, String operation, Path file, Path other, long size) {
        return TIME.format(at) + ";" + operation + ";" + csv(file.toString()) + ";"
                + (other == null ? "" : csv(other.toString())) + ";" + (size < 0 ? "" : size);
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    private static String csv(String value) {
        return value.contains(";") || value.contains("\"") ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    private static String describe(Duration d) {
        long minutes = d.toMinutes();
        return minutes < 60 ? Math.max(1, minutes) + " min" : minutes / 60 + " h " + minutes % 60 + " min";
    }
}
