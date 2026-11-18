package com.photon.phonearchive;

import com.photon.duplicates.MediaFormat;
import com.photon.duplicates.scan.Fingerprints;
import com.photon.duplicates.scan.HashCache;
import com.photon.duplicates.scan.ProtectedPaths;
import com.photon.duplicates.scan.ScannedFile;
import com.photon.thumbnails.ThumbnailGenerator;
import com.photon.thumbnails.ThumbnailMaker;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Archiwizacja zdjęć i filmów: każdy plik z folderu źródłowego, którego jeszcze nie ma w archiwum
 * (w żadnym folderze {@code RRRR-MM-DD}), jest przenoszony do folderu z dzisiejszą datą.
 * Pliki, które już są w archiwum, zostają na miejscu i trafiają do listy duplikatów.
 * <p>
 * Duplikat = identyczna zawartość (SHA-256). Sumy liczone są tylko dla plików o równym rozmiarze.
 * Pliki z chronionych folderów ({@code __…}) są kopiowane, a nie przenoszone.
 */
public class PhoneArchiver {

    public interface Progress {
        void update(String message, long done, long total);
    }

    public static class CancelledException extends RuntimeException {
        public CancelledException() {
            super("Archiwizacja została przerwana");
        }
    }

    /** Plik źródłowy, który już jest w archiwum. */
    public record Duplicate(Path source, Path existing, long size) {
    }

    /**
     * @param moved          pliki przeniesione do archiwum (ścieżki docelowe)
     * @param copied         pliki skopiowane, bo leżą w chronionym folderze źródłowym (ścieżki docelowe)
     * @param duplicates     pliki, które już były w archiwum
     * @param dateFolders    liczba folderów RRRR-MM-DD w archiwum przed startem
     * @param archivedBytes  łączny rozmiar przeniesionych i skopiowanych plików
     * @param dayFolder      folder z dzisiejszą datą
     * @param thumbnails     miniatury (gdy włączone)
     */
    public record Summary(List<Path> moved, List<Path> copied, List<Duplicate> duplicates, int dateFolders,
                          List<String> errors, long archivedBytes, Path dayFolder, Duration elapsed,
                          ThumbnailStats thumbnails, int waiting) {
    }

    /** Decyzja, czy plik źródłowy można teraz archiwizować. */
    public enum FileState {
        /** Gotowy — archiwizujemy. */
        READY,
        /** Jeszcze się zmienia (np. trwa przesyłanie) — spróbujemy później, liczony jako „czeka”. */
        WAIT,
        /** Pomijamy bez liczenia (np. duplikat zgłoszony już wcześniej). */
        IGNORE
    }

    /** Filtr plików źródłowych — używany przez archiwizację w tle. */
    public interface FileGate {
        FileState check(Path file) throws IOException;
    }

    /**
     * @param created     utworzone miniatury
     * @param existing    pominięte, bo miniatura o tej nazwie już była
     * @param unsupported zdjęcia w formatach innych niż JPEG (bez miniatury)
     * @param folder      folder, do którego trafiły miniatury (null = miniatury wyłączone)
     */
    public record ThumbnailStats(int created, int existing, int unsupported, Path folder) {
        public static final ThumbnailStats NONE = new ThumbnailStats(0, 0, 0, null);
    }

    /**
     * @param removed          usunięte lub przeniesione pliki źródłowe (ścieżki przed operacją)
     * @param skippedProtected pominięte, bo usunięcie złamałoby ochronę folderu {@code __…}
     */
    public record RemovalOutcome(List<Path> removed, int skippedProtected, List<String> errors) {
    }

    public static final DateTimeFormatter DAY_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern DAY_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final String TMP_SUFFIX = ".photon-tmp";

    private final PhoneArchiveOptions options;
    private final LocalDate today;
    private final HashCache cache;
    /** Sumy policzone w tym przebiegu (także dla plików źródłowych, których nie ma w pamięci podręcznej). */
    private final Map<Path, String> hashes = new HashMap<>();
    private FileGate gate = file -> FileState.READY;

    /** @param cache pamięć podręczna sum plików archiwum (null = bez pamięci) */
    public PhoneArchiver(PhoneArchiveOptions options, LocalDate today, HashCache cache) {
        this.options = options;
        this.today = today;
        this.cache = cache;
    }

    /** Ustawia filtr plików źródłowych (domyślnie wszystkie są gotowe). */
    public PhoneArchiver withGate(FileGate gate) {
        this.gate = gate;
        return this;
    }

    // =====================================================================
    // Foldery RRRR-MM-DD
    // =====================================================================

    /** Czy nazwa jest poprawną datą w konwencji {@code RRRR-MM-DD} (np. 2016-02-23). */
    public static boolean isDateFolderName(String name) {
        if (name == null || !DAY_PATTERN.matcher(name).matches()) {
            return false;
        }
        try {
            LocalDate.parse(name, DAY_FORMAT);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    /** Bezpośrednie podfoldery archiwum nazwane datą (pusta lista, gdy folder nie istnieje). */
    public static List<Path> dateFolders(Path target) {
        List<Path> folders = new ArrayList<>();
        if (target == null || !Files.isDirectory(target)) {
            return folders;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(target)) {
            for (Path child : stream) {
                if (Files.isDirectory(child) && isDateFolderName(child.getFileName().toString())) {
                    folders.add(child);
                }
            }
        } catch (IOException e) {
            return folders;
        }
        Collections.sort(folders);
        return folders;
    }

    public static boolean isPhoto(Path file) {
        return MediaFormat.forExtension(ScannedFile.extensionOf(file.getFileName().toString()))
                .map(f -> f.getKind() == MediaFormat.Kind.PHOTO).orElse(false);
    }

    public static boolean isMedia(Path file) {
        return MediaFormat.forExtension(ScannedFile.extensionOf(file.getFileName().toString())).isPresent();
    }

    // =====================================================================
    // Archiwizacja
    // =====================================================================

    public Summary run(Progress progress, BooleanSupplier cancelled) {
        long start = System.nanoTime();
        Path source = options.source().toAbsolutePath().normalize();
        Path target = options.target().toAbsolutePath().normalize();
        String day = today.format(DAY_FORMAT);
        Path dayFolder = target.resolve(day);
        Path thumbFolder = options.makeThumbnails()
                ? options.thumbnailTarget().toAbsolutePath().normalize().resolve(day) : null;
        int thumbsCreated = 0;
        int thumbsExisting = 0;
        int thumbsUnsupported = 0;
        int waiting = 0;
        List<String> errors = new ArrayList<>();

        progress.update("Wyszukiwanie zdjęć i filmów w folderze archiwizowanym…", -1, 1);
        List<Path> media = collectSource(source, target, errors, cancelled);

        progress.update("Indeksowanie archiwum…", -1, 1);
        List<Path> folders = dateFolders(target);
        Map<Long, List<Path>> index = new HashMap<>();
        for (Path folder : folders) {
            indexFolder(folder, index, errors, cancelled);
        }

        List<Path> moved = new ArrayList<>();
        List<Path> copied = new ArrayList<>();
        List<Duplicate> duplicates = new ArrayList<>();
        long archivedBytes = 0;
        int total = media.size();
        for (int i = 0; i < total; i++) {
            checkCancelled(cancelled);
            Path file = media.get(i);
            progress.update("Archiwizacja: " + (i + 1) + " z " + total + "  •  " + file.getFileName(), i, total);
            try {
                FileState state = gate.check(file);
                if (state == FileState.WAIT) {
                    waiting++;
                    continue;
                }
                if (state == FileState.IGNORE) {
                    continue;
                }
                long size = Files.size(file);
                Path existing = findInArchive(file, size, index);
                if (existing != null) {
                    duplicates.add(new Duplicate(file, existing, size));
                    continue;
                }
                Files.createDirectories(dayFolder);
                Path destination = uniqueName(dayFolder.resolve(file.getFileName().toString()));
                boolean keepOriginal = ProtectedPaths.isProtected(file);
                transfer(file, destination, keepOriginal);
                (keepOriginal ? copied : moved).add(destination);
                archivedBytes += size;
                index.computeIfAbsent(size, k -> new ArrayList<>()).add(destination);
                String hash = hashes.remove(file);
                if (hash != null) {
                    hashes.put(destination, hash);
                }
                if (thumbFolder != null && isPhoto(destination)) {
                    if (!ThumbnailGenerator.isJpeg(destination)) {
                        thumbsUnsupported++;
                    } else {
                        Path thumbnail = thumbFolder.resolve(destination.getFileName().toString());
                        if (Files.exists(thumbnail)) {
                            thumbsExisting++;
                        } else {
                            progress.update("Miniatura: " + (i + 1) + " z " + total + "  •  "
                                    + destination.getFileName(), i, total);
                            try {
                                ThumbnailMaker.make(destination, thumbnail, options.thumbnailSide());
                                thumbsCreated++;
                            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                                // Plik jest już bezpiecznie w archiwum — błąd dotyczy tylko miniatury.
                                errors.add(destination + ": nie utworzono miniatury — "
                                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                            }
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                if (e instanceof CancelledException ce) {
                    throw ce;
                }
                errors.add(file + ": " + message(e));
            }
        }
        progress.update("Gotowe", total, total);
        return new Summary(List.copyOf(moved), List.copyOf(copied), List.copyOf(duplicates), folders.size(),
                List.copyOf(errors), archivedBytes, dayFolder, Duration.ofNanos(System.nanoTime() - start),
                thumbFolder == null ? ThumbnailStats.NONE
                        : new ThumbnailStats(thumbsCreated, thumbsExisting, thumbsUnsupported, thumbFolder),
                waiting);
    }

    private List<Path> collectSource(Path source, Path target, List<String> errors, BooleanSupplier cancelled) {
        List<Path> media = new ArrayList<>();
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    checkCancelled(cancelled);
                    if (dir.equals(target)) {
                        return FileVisitResult.SKIP_SUBTREE; // archiwum wewnątrz folderu źródłowego
                    }
                    if (!dir.equals(source) && !options.includeSubfolders()) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && isMedia(file)) {
                        media.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    errors.add(file + ": " + message(e));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            errors.add(source + ": " + message(e));
        }
        Collections.sort(media);
        return media;
    }

    private void indexFolder(Path folder, Map<Long, List<Path>> index, List<String> errors,
                             BooleanSupplier cancelled) {
        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    checkCancelled(cancelled);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && !file.getFileName().toString().endsWith(TMP_SUFFIX)) {
                        index.computeIfAbsent(attrs.size(), k -> new ArrayList<>()).add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    errors.add(file + ": " + message(e));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            errors.add(folder + ": " + message(e));
        }
    }

    /** Plik archiwum o tej samej zawartości albo {@code null}. */
    private Path findInArchive(Path file, long size, Map<Long, List<Path>> index) throws IOException {
        List<Path> candidates = index.get(size);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        String hash = hashes.get(file);
        if (hash == null) {
            hash = Fingerprints.sha256(file);
            hashes.put(file, hash);
        }
        for (Path candidate : candidates) {
            try {
                if (hash.equals(archiveHash(candidate))) {
                    return candidate;
                }
            } catch (IOException e) {
                // Nieczytelny plik archiwum nie jest duplikatem — sprawdzamy kolejne.
            }
        }
        return null;
    }

    private String archiveHash(Path file) throws IOException {
        String hash = hashes.get(file);
        if (hash == null) {
            hash = cache != null ? cache.sha256(file) : Fingerprints.sha256(file);
            hashes.put(file, hash);
        }
        return hash;
    }

    /** Przenosi (albo kopiuje) plik tak, by w razie błędu nie zostawić w archiwum niepełnej kopii. */
    private static void transfer(Path file, Path destination, boolean keepOriginal) throws IOException {
        if (!keepOriginal) {
            try {
                // Ten sam dysk: zwykła zmiana nazwy, bez kopiowania danych.
                Files.move(file, destination);
                return;
            } catch (IOException e) {
                if (Files.exists(destination) || !Files.exists(file)) {
                    throw e;
                }
                // Inny dysk lub inny problem — próbujemy bezpiecznie: kopia, weryfikacja, usunięcie.
            }
        }
        Path tmp = destination.resolveSibling(destination.getFileName() + TMP_SUFFIX);
        try {
            Files.copy(file, tmp, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(tmp) != Files.size(file)) {
                throw new IOException("kopia ma inny rozmiar niż oryginał");
            }
            Files.move(tmp, destination);
        } finally {
            Files.deleteIfExists(tmp);
        }
        if (!keepOriginal) {
            Files.delete(file);
        }
    }

    /** {@code IMG.jpg}, a gdy zajęte (inna zawartość): {@code IMG (1).jpg}, {@code IMG (2).jpg}… */
    static Path uniqueName(Path path) {
        if (!Files.exists(path)) {
            return path;
        }
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot <= 0 ? name : name.substring(0, dot);
        String ext = dot <= 0 ? "" : name.substring(dot);
        for (int i = 1; ; i++) {
            Path candidate = path.resolveSibling(base + " (" + i + ")" + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    // =====================================================================
    // Usuwanie duplikatów ze źródła
    // =====================================================================

    /**
     * Usuwa ze źródła pliki, które już są w archiwum. Przed usunięciem sprawdza, czy kopia w archiwum
     * nadal istnieje i ma ten sam rozmiar. Plik z chronionego folderu ({@code __…}) jest usuwany tylko
     * wtedy, gdy kopia z archiwum leży w tym samym chronionym folderze.
     *
     * @param toTrash do kosza systemowego zamiast trwałego usunięcia
     */
    public static RemovalOutcome removeFromSource(List<Duplicate> duplicates, boolean toTrash) {
        return processDuplicates(duplicates, file -> {
            if (toTrash) {
                if (!Desktop.getDesktop().moveToTrash(file.toFile())) {
                    throw new IOException("nie udało się przenieść do kosza");
                }
            } else {
                Files.delete(file);
            }
        });
    }

    /**
     * Przenosi ze źródła pliki, które już są w archiwum, do wskazanego folderu z zachowaniem struktury
     * podfolderów względem folderu archiwizowanego. Gdy plik o tej nazwie już tam jest, dostaje sufiks
     * {@code (1)}, {@code (2)}… Zabezpieczenia jak w {@link #removeFromSource}.
     *
     * @param source folder archiwizowany (do odtworzenia struktury podfolderów)
     * @param folder folder na duplikaty
     */
    public static RemovalOutcome moveDuplicates(List<Duplicate> duplicates, Path source, Path folder) {
        Path root = source.toAbsolutePath().normalize();
        Path destinationRoot = folder.toAbsolutePath().normalize();
        return processDuplicates(duplicates, file -> {
            Path absolute = file.toAbsolutePath().normalize();
            Path relative = absolute.startsWith(root) ? root.relativize(absolute) : absolute.getFileName();
            Path destination = uniqueName(destinationRoot.resolve(relative.toString()));
            Files.createDirectories(destination.getParent());
            transfer(file, destination, false);
        });
    }

    private interface FileOperation {
        void apply(Path file) throws IOException;
    }

    /**
     * Wspólne zabezpieczenia: kopia w archiwum musi nadal istnieć i mieć ten sam rozmiar, a plik z chronionego
     * folderu ({@code __…}) jest ruszany tylko wtedy, gdy kopia z archiwum leży w tym samym chronionym folderze.
     */
    private static RemovalOutcome processDuplicates(List<Duplicate> duplicates, FileOperation operation) {
        List<Path> processed = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int skippedProtected = 0;
        for (Duplicate duplicate : duplicates) {
            Path file = duplicate.source();
            try {
                Path existing = duplicate.existing().toAbsolutePath().normalize();
                boolean protectedCopyKept = ProtectedPaths.protectedDirs(file).stream().allMatch(existing::startsWith);
                if (!protectedCopyKept) {
                    skippedProtected++;
                    continue;
                }
                if (!Files.isRegularFile(file)) {
                    continue; // usunięty w międzyczasie
                }
                if (!Files.isRegularFile(existing) || Files.size(existing) != Files.size(file)) {
                    throw new IOException("kopia w archiwum zniknęła lub się zmieniła (" + existing + ")");
                }
                operation.apply(file);
                processed.add(file);
            } catch (IOException | RuntimeException e) {
                errors.add(file + ": " + message(e));
            }
        }
        return new RemovalOutcome(List.copyOf(processed), skippedProtected, List.copyOf(errors));
    }

    /** Czy system pozwala przenosić pliki do kosza. */
    public static boolean trashSupported() {
        try {
            return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancelledException();
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
