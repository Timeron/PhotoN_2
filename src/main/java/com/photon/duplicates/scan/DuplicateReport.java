package com.photon.duplicates.scan;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Raport CSV z wynikami (separator „;” i UTF-8 z BOM — poprawnie otwiera się w polskim Excelu).
 */
public final class DuplicateReport {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")
            .withZone(ZoneId.systemDefault());

    private DuplicateReport() {
    }

    /** Proponowana nazwa pliku raportu, np. „Duplikaty 2026-11-17 22-40-00.csv”. */
    public static String defaultFileName(ScanResult result) {
        return "Duplikaty " + FILE_STAMP.format(result.finishedAt()) + ".csv";
    }

    public static void write(List<DuplicateGroup> groups, Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write('﻿');
            out.write(String.join(";", "Grupa", "Rola", "Nazwa", "Typ", "Rozmiar (B)",
                    "Data modyfikacji", "Ścieżka", "Liczba plików w grupie", "Łączny rozmiar grupy (B)"));
            out.write("\r\n");
            int groupNo = 0;
            for (DuplicateGroup group : groups) {
                groupNo++;
                for (ScannedFile f : group.files()) {
                    out.write(String.join(";",
                            Integer.toString(groupNo),
                            f == group.original() ? "Oryginał" : "Duplikat",
                            csv(f.fileName()),
                            csv(f.extension().toUpperCase()),
                            Long.toString(f.size()),
                            DATE.format(f.modified()),
                            csv(f.path().toString()),
                            Integer.toString(group.count()),
                            Long.toString(group.totalSize())));
                    out.write("\r\n");
                }
            }
        }
    }

    private static String csv(String value) {
        if (value.contains(";") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
