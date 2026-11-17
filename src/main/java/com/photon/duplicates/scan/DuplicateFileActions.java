package com.photon.duplicates.scan;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Operacje na plikach duplikatów: kosz, trwałe usunięcie, przeniesienie do folderu.
 * Oryginały nigdy nie są ruszane — operacje dotyczą wyłącznie {@link DuplicateGroup#duplicates()}.
 * Pliki z katalogów chronionych ({@link ProtectedPaths}) są pomijane przy operacjach automatycznych
 * i mogą być usuwane tylko ręcznie ({@link #removeManually}).
 */
public final class DuplicateFileActions {

    /**
     * @param processed        pliki przeniesione/usunięte
     * @param errors           komunikaty błędów
     * @param skippedProtected pliki pominięte, bo leżą w chronionym katalogu ({@link ProtectedPaths})
     */
    public record Outcome(Set<Path> processed, List<String> errors, int skippedProtected) {
    }

    public enum Mode { TRASH, DELETE, MOVE }

    private DuplicateFileActions() {
    }

    /**
     * @param target    folder docelowy (tylko {@link Mode#MOVE})
     * @param locations lokalizacje skanowania — zachowujemy względną strukturę folderów
     * @param log       plik dziennika operacji albo {@code null}
     */
    public static Outcome apply(List<DuplicateGroup> groups, Mode mode, Path target, List<Path> locations,
                                Path log, BooleanSupplier cancelled) {
        Set<Path> processed = new HashSet<>();
        List<String> errors = new ArrayList<>();
        List<String> logLines = new ArrayList<>();
        int skippedProtected = 0;

        for (DuplicateGroup group : groups) {
            for (ScannedFile file : group.duplicates()) {
                if (cancelled.getAsBoolean()) {
                    break;
                }
                if (ProtectedPaths.isProtected(file.path())) {
                    skippedProtected++;
                    continue;
                }
                process(group, file, mode, target, locations, processed, errors, logLines, "");
            }
        }
        writeLog(log, logLines, errors);
        return new Outcome(processed, errors, skippedProtected);
    }

    /**
     * Ręczne usunięcie jednego pliku wskazanego przez użytkownika (kosz albo trwale).
     * Plik z chronionego katalogu można usunąć tylko wtedy, gdy w tym katalogu zostaje inna kopia.
     */
    public static Outcome removeManually(DuplicateGroup group, ScannedFile file, boolean toTrash, Path log) {
        Set<Path> processed = new HashSet<>();
        List<String> errors = new ArrayList<>();
        List<String> logLines = new ArrayList<>();
        Path blocking = ProtectedPaths.blockingDir(group, file);
        if (blocking != null) {
            errors.add(file.path() + ": to ostatnia kopia w chronionym katalogu " + blocking);
        } else {
            process(group, file, toTrash ? Mode.TRASH : Mode.DELETE, null, List.of(),
                    processed, errors, logLines, " [ręcznie]");
        }
        writeLog(log, logLines, errors);
        return new Outcome(processed, errors, 0);
    }

    private static void process(DuplicateGroup group, ScannedFile file, Mode mode, Path target, List<Path> locations,
                                Set<Path> processed, List<String> errors, List<String> logLines, String suffix) {
        DateTimeFormatter time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        Path path = file.path();
        try {
            String entry;
            switch (mode) {
                case TRASH -> {
                    if (!Desktop.isDesktopSupported()
                            || !Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
                        throw new IOException("System nie obsługuje przenoszenia do kosza");
                    }
                    if (!Desktop.getDesktop().moveToTrash(path.toFile())) {
                        throw new IOException("Nie udało się przenieść do kosza");
                    }
                    entry = "KOSZ\t" + path;
                }
                case DELETE -> {
                    Files.delete(path);
                    entry = "USUNIĘTO\t" + path;
                }
                case MOVE -> {
                    Path destination = uniqueTarget(destinationFor(file, target, locations));
                    Files.createDirectories(destination.getParent());
                    Files.move(path, destination);
                    entry = "PRZENIESIONO\t" + path + "\t->\t" + destination;
                }
                default -> throw new IllegalStateException();
            }
            processed.add(path);
            logLines.add(LocalDateTime.now().format(time) + "\t" + entry
                    + "\t(oryginał: " + group.original().path() + ")" + suffix);
        } catch (IOException | RuntimeException e) {
            errors.add(path + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    private static void writeLog(Path log, List<String> logLines, List<String> errors) {
        if (log != null && !logLines.isEmpty()) {
            try {
                Files.createDirectories(log.toAbsolutePath().getParent());
                Files.write(log, logLines, java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                errors.add("Nie udało się zapisać dziennika operacji: " + e.getMessage());
            }
        }
    }

    /**
     * Cel przeniesienia: {@code target/<nazwa lokalizacji>/<ścieżka względna>}, aby pliki o tej samej
     * nazwie z różnych folderów się nie mieszały.
     */
    static Path destinationFor(ScannedFile file, Path target, List<Path> locations) {
        Path source = file.path();
        if (file.locationIndex() >= 0 && file.locationIndex() < locations.size()) {
            Path root = locations.get(file.locationIndex()).toAbsolutePath().normalize();
            if (source.startsWith(root)) {
                String rootName = root.getFileName() != null
                        ? root.getFileName().toString()
                        : root.toString().replaceAll("[:\\\\/]", "");
                return target.resolve(rootName).resolve(root.relativize(source).toString());
            }
        }
        return target.resolve(source.getFileName().toString());
    }

    /** Dokleja „ (2)”, „ (3)”… gdy plik docelowy już istnieje. */
    static Path uniqueTarget(Path path) {
        if (!Files.exists(path)) {
            return path;
        }
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; ; i++) {
            Path candidate = path.resolveSibling(base + " (" + i + ")" + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }
}
