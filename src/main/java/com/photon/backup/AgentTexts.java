package com.photon.backup;

import com.photon.phonearchive.PhoneArchiver;
import com.photon.ui.Texts;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Teksty agenta kopii zapasowej: wpisy dziennika i treść powiadomień. */
final class AgentTexts {

    private AgentTexts() {
    }

    /** „15 sekund”, „minutę”, „15 minut”… */
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

    /** Wpis dziennika o przebiegu (albo „Bez zmian.”). */
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
        if (report.summary() != null && !report.summary().incomplete().isEmpty()) {
            parts.add("niekompletne (czekają): " + report.summary().incomplete().size());
        }
        if (!report.errors().isEmpty()) {
            parts.add("błędy: " + report.errors().size());
        }
        String text = String.join(", ", parts);
        return text.isEmpty() ? "Bez zmian." : Character.toUpperCase(text.charAt(0)) + text.substring(1) + ".";
    }

    /**
     * Treść powiadomienia o przebiegu — tylko to, co warte uwagi: zarchiwizowane pliki, duplikaty, błędy.
     *
     * @return tekst albo null, gdy nie ma o czym powiadamiać (np. tylko pliki w trakcie przesyłania)
     */
    static String notification(ArchiveWatcher.CycleReport report) {
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
        return parts.isEmpty() ? null : String.join("\n", parts);
    }

    /** Ostrzeżenie o plikach, których przesyłanie stoi. */
    static String stalled(List<Path> files) {
        return "Przesyłanie stoi od ponad " + describe(ArchiveWatcher.STALL_AFTER) + ": "
                + Texts.count(files.size(), "plik", "pliki", "plików") + " niekompletne albo puste ("
                + files.stream().limit(5).map(p -> String.valueOf(p.getFileName())).collect(Collectors.joining(", "))
                + (files.size() > 5 ? ", …" : "") + "). Wyślij je z telefonu ponownie — zostają w folderze "
                + "archiwizowanym i trafią do archiwum, gdy będą kompletne.";
    }
}
