package com.photon.duplicates.scan;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Operacje na plikach duplikatów: kosz, trwałe usunięcie, przeniesienie do folderu.
 * Działanie automatyczne obejmuje kopie ({@link #candidates}): zwykle wszystko poza oryginałem, a w grupie
 * z plikami oznaczonymi ręcznie jako chronione — wszystko poza nimi (także oryginał).
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
        return apply(groups, Set.of(), mode, target, locations, log, cancelled);
    }

    /**
     * @param kept pliki oznaczone ręcznie jako chronione — zostają, a pozostałe pliki ich grup są przetwarzane
     */
    public static Outcome apply(List<DuplicateGroup> groups, Set<Path> kept, Mode mode, Path target,
                                List<Path> locations, Path log, BooleanSupplier cancelled) {
        Set<Path> processed = new HashSet<>();
        List<String> errors = new ArrayList<>();
        List<String> logLines = new ArrayList<>();
        int skippedProtected = 0;

        for (DuplicateGroup group : groups) {
            Path keeper = keeper(group, kept);
            for (ScannedFile file : candidates(group, kept)) {
                if (cancelled.getAsBoolean()) {
                    break;
                }
                if (ProtectedPaths.isProtected(file.path())) {
                    skippedProtected++;
                    continue;
                }
                process(keeper, file, mode, target, locations, processed, errors, logLines, "");
            }
        }
        writeLog(log, logLines, errors);
        return new Outcome(processed, errors, skippedProtected);
    }

    /**
     * Pliki grupy, które obejmie działanie automatyczne: wszystkie poza ręcznie chronionymi, a gdy w grupie
     * nie ma takich — wszystkie poza oryginałem. (Pliki z katalogów „__…” i tak są potem pomijane.)
     */
    public static List<ScannedFile> candidates(DuplicateGroup group, Set<Path> kept) {
        if (group.files().stream().noneMatch(f -> kept.contains(f.path()))) {
            return group.duplicates();
        }
        return group.files().stream().filter(f -> !kept.contains(f.path())).toList();
    }

    /** Plik, który zostaje jako wzorzec grupy (do dziennika). */
    private static Path keeper(DuplicateGroup group, Set<Path> kept) {
        return group.files().stream().map(ScannedFile::path).filter(kept::contains).findFirst()
                .orElse(group.original().path());
    }

    // =====================================================================
    // Usuwanie zaznaczonych plików
    // =====================================================================

    /**
     * Plan ręcznego usunięcia zaznaczonych plików.
     *
     * @param toRemove pliki, które można usunąć (pogrupowane)
     * @param refused  pliki, których usunąć nie wolno, z powodem
     */
    public record RemovalPlan(Map<DuplicateGroup, List<ScannedFile>> toRemove, List<String> refused) {
        public int count() {
            return toRemove.values().stream().mapToInt(List::size).sum();
        }

        public long bytes() {
            return toRemove.values().stream().flatMap(List::stream).mapToLong(ScannedFile::size).sum();
        }
    }

    /**
     * Sprawdza, które z zaznaczonych plików można usunąć:
     * <ul>
     *   <li>w każdej grupie musi zostać co najmniej jeden plik,</li>
     *   <li>pliki oznaczone ręcznie jako chronione nie są usuwane,</li>
     *   <li>w każdym katalogu „__…” zaznaczonego pliku musi zostać inna kopia z grupy.</li>
     * </ul>
     */
    public static RemovalPlan planRemoval(Map<DuplicateGroup, ? extends Collection<ScannedFile>> selection,
                                          Set<Path> kept) {
        Map<DuplicateGroup, List<ScannedFile>> toRemove = new LinkedHashMap<>();
        List<String> refused = new ArrayList<>();
        selection.forEach((group, selected) -> {
            Set<Path> selectedPaths = new HashSet<>();
            for (ScannedFile file : selected) {
                if (kept.contains(file.path())) {
                    refused.add(file.path() + ": plik jest oznaczony jako chroniony");
                } else {
                    selectedPaths.add(file.path());
                }
            }
            List<ScannedFile> remaining = group.files().stream()
                    .filter(f -> !selectedPaths.contains(f.path())).toList();
            if (remaining.isEmpty()) {
                refused.add(group.name() + ": zaznaczono wszystkie pliki grupy — co najmniej jeden musi zostać");
                return;
            }
            List<ScannedFile> removable = new ArrayList<>();
            for (ScannedFile file : group.files()) {
                if (!selectedPaths.contains(file.path())) {
                    continue;
                }
                Path blocking = ProtectedPaths.blockingDir(remaining, file);
                if (blocking != null) {
                    refused.add(file.path() + ": to ostatnia kopia w chronionym katalogu " + blocking);
                } else {
                    removable.add(file);
                }
            }
            if (!removable.isEmpty()) {
                toRemove.put(group, removable);
            }
        });
        return new RemovalPlan(toRemove, refused);
    }

    /** Wykonuje plan ręcznego usunięcia (kosz albo trwale). */
    public static Outcome removeSelected(RemovalPlan plan, Set<Path> kept, boolean toTrash, Path log) {
        Set<Path> processed = new HashSet<>();
        List<String> errors = new ArrayList<>();
        List<String> logLines = new ArrayList<>();
        plan.toRemove().forEach((group, files) -> {
            Set<Path> removing = new HashSet<>();
            files.forEach(f -> removing.add(f.path()));
            Path keeper = group.files().stream().map(ScannedFile::path).filter(p -> !removing.contains(p))
                    .filter(kept::contains).findFirst()
                    .orElse(group.files().stream().map(ScannedFile::path).filter(p -> !removing.contains(p))
                            .findFirst().orElseThrow());
            for (ScannedFile file : files) {
                process(keeper, file, toTrash ? Mode.TRASH : Mode.DELETE, null, List.of(),
                        processed, errors, logLines, " [ręcznie, zaznaczone]");
            }
        });
        writeLog(log, logLines, errors);
        return new Outcome(processed, errors, 0);
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
            Path keeper = group.files().stream().map(ScannedFile::path).filter(p -> !p.equals(file.path()))
                    .findFirst().orElse(group.original().path());
            process(keeper, file, toTrash ? Mode.TRASH : Mode.DELETE, null, List.of(),
                    processed, errors, logLines, " [ręcznie]");
        }
        writeLog(log, logLines, errors);
        return new Outcome(processed, errors, 0);
    }

    private static void process(Path keeper, ScannedFile file, Mode mode, Path target, List<Path> locations,
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
                    + "\t(zostaje: " + keeper + ")" + suffix);
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
