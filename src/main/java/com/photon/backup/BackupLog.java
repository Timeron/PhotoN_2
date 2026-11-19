package com.photon.backup;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dziennik kopii zapasowej (tekst, jedna linia na zdarzenie). Pisany przez agenta, czytany przez PhotoN.
 */
public final class BackupLog {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    /** Powyżej tego rozmiaru dziennik jest przenoszony do {@code .1} przy starcie agenta. */
    private static final long ROTATE_BYTES = 5L * 1024 * 1024;
    private static final int TAIL_BYTES = 128 * 1024;

    private BackupLog() {
    }

    public static synchronized void append(Path file, Instant at, String text) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, TIME.format(at) + "  " + text + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Nie udało się zapisać dziennika kopii zapasowej: " + e.getMessage());
        }
    }

    /** Przenosi duży dziennik do pliku {@code .1} (poprzedni {@code .1} jest zastępowany). */
    public static void rotateIfLarge(Path file) {
        try {
            if (Files.isRegularFile(file) && Files.size(file) > ROTATE_BYTES) {
                Files.move(file, file.resolveSibling(file.getFileName() + ".1"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("Nie udało się zrotować dziennika: " + e.getMessage());
        }
    }

    /** Ostatnie wpisy, najnowsze pierwsze. */
    public static List<String> tail(Path file, int maxLines) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            long from = Math.max(0, length - TAIL_BYTES);
            byte[] bytes = new byte[(int) (length - from)];
            raf.seek(from);
            raf.readFully(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>(List.of(text.split("\\R")));
            if (from > 0 && !lines.isEmpty()) {
                lines.remove(0); // pierwsza linia mogła zostać ucięta
            }
            lines.removeIf(String::isBlank);
            Collections.reverse(lines);
            return lines.size() > maxLines ? List.copyOf(lines.subList(0, maxLines)) : List.copyOf(lines);
        } catch (IOException e) {
            return List.of();
        }
    }
}
