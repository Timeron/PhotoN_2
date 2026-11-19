package com.photon.phonearchive;

import com.photon.duplicates.MediaFormat;
import com.photon.duplicates.scan.Fingerprints;
import com.photon.duplicates.scan.HashCache;
import com.photon.duplicates.scan.ProtectedPaths;
import com.photon.duplicates.scan.ScannedFile;
import com.photon.thumbnails.ThumbnailGenerator;
import com.photon.thumbnails.ThumbnailMaker;
import com.photon.remote.Locations;
import com.photon.ui.Texts;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
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
import com.photon.CancelledException;

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


    /**
     * Archiwum (np. dysk sieciowy) jest niedostępne albo nie dało się go w całości odczytać. Przebieg jest
     * przerywany, zanim cokolwiek zostanie przeniesione bez sprawdzenia duplikatów — pliki zostają w źródle.
     */
    public static class ArchiveUnavailableException extends RuntimeException {
        public ArchiveUnavailableException(String message) {
            super(message);
        }
    }

    /** Za mało miejsca na dysku archiwum (albo miniatur) — przebieg przerwany, pliki zostały w źródle. */
    public static class InsufficientSpaceException extends ArchiveUnavailableException {
        public InsufficientSpaceException(String message) {
            super(message);
        }
    }

    /** Ile miejsca musi zostać wolne na dysku archiwum i miniatur po przeniesieniu pliku. */
    public static final long MIN_FREE_BYTES = 1024L * 1024 * 1024;

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
     * @param waiting        pliki, które czekają (w trakcie przesyłania albo niekompletne)
     * @param incomplete     pliki niekompletne wg budowy pliku ({@link MediaIntegrity}) — zostały w źródle
     */
    public record Summary(List<Path> moved, List<Path> copied, List<Duplicate> duplicates, int dateFolders,
                          List<String> errors, long archivedBytes, Path dayFolder, Duration elapsed,
                          ThumbnailStats thumbnails, int waiting, List<Path> incomplete) {
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

    private final PhoneArchiveOptions options;
    private final LocalDate today;
    private final HashCache cache;
    /** Sumy policzone w tym przebiegu (także dla plików źródłowych, których nie ma w pamięci podręcznej). */
    private final Map<Path, String> hashes = new HashMap<>();
    private FileGate gate = file -> FileState.READY;
    private ArchiveIndex archiveIndex;
    private boolean expectDateFolders;
    private long minFreeBytes = MIN_FREE_BYTES;

    /** @param cache pamięć podręczna sum plików archiwum (null = bez pamięci) */
    public PhoneArchiver(PhoneArchiveOptions options, LocalDate today, HashCache cache) {
        this.options = options;
        this.today = today;
        this.cache = cache;
    }

    /** Spis archiwum pamiętany między przebiegami (domyślnie budowany za każdym razem). */
    public PhoneArchiver withIndex(ArchiveIndex index) {
        this.archiveIndex = index;
        return this;
    }

    /**
     * Archiwum miało foldery {@code RRRR-MM-DD}: jeśli teraz nie widać żadnego (albo samego archiwum),
     * uznajemy je za niedostępne (np. odłączony dysk sieciowy), a nie za puste lub nowe.
     */
    public PhoneArchiver expectingDateFolders(boolean expect) {
        this.expectDateFolders = expect;
        return this;
    }

    /** Ile miejsca musi zostać wolne na dysku archiwum i miniatur (domyślnie {@link #MIN_FREE_BYTES}). */
    public PhoneArchiver withMinFreeBytes(long bytes) {
        this.minFreeBytes = bytes;
        return this;
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
        if (target == null || !Files.isDirectory(target)) {
            return new ArrayList<>();
        }
        try {
            return listDateFolders(target);
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    /** Jak {@link #dateFolders}, ale błąd odczytu jest zgłaszany, a nie zamieniany na pustą listę. */
    static List<Path> listDateFolders(Path target) throws IOException {
        List<Path> folders = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(target)) {
            for (Path child : stream) {
                if (isDateFolderName(child.getFileName().toString()) && Files.isDirectory(child)) {
                    folders.add(child);
                }
            }
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        }
        Collections.sort(folders);
        return folders;
    }

    /**
     * Sprawdza, czy archiwum jest dostępne, i zwraca jego foldery z datą.
     *
     * @throws ArchiveUnavailableException gdy archiwum nie istnieje (a nie można go utworzyć), nie daje się
     *                                     odczytać albo nagle nie ma w nim folderów z datą
     */
    private List<Path> openArchive(Path target) {
        if (!Files.isDirectory(target)) {
            Path parent = target.getParent();
            if (expectDateFolders) {
                throw new ArchiveUnavailableException("Folder archiwum jest niedostępny: " + target
                        + " (wcześniej istniał — czy dysk jest podłączony?).");
            }
            if (parent == null || !Files.isDirectory(parent)) {
                throw new ArchiveUnavailableException("Folder archiwum jest niedostępny: " + target
                        + " (nie istnieje ani on, ani folder nadrzędny — czy dysk jest podłączony?).");
            }
            return List.of(); // nowe archiwum — powstanie przy pierwszym przeniesionym pliku
        }
        List<Path> folders;
        try {
            folders = listDateFolders(target);
        } catch (IOException e) {
            throw new ArchiveUnavailableException("Nie udało się odczytać folderu archiwum " + target + ": " + Texts.errorMessage(e));
        }
        if (folders.isEmpty() && expectDateFolders) {
            throw new ArchiveUnavailableException("W archiwum " + target + " nie widać żadnego folderu z datą, "
                    + "choć wcześniej były — dysk może być niedostępny.");
        }
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
        return new Run(progress, cancelled).execute();
    }

    /** Jeden przebieg archiwizacji: jego stan (listy, liczniki) i kolejne kroki. */
    private final class Run {
        private final long start = System.nanoTime();
        private final Progress progress;
        private final BooleanSupplier cancelled;
        private final Path source;
        private final Path target;
        private final Path dayFolder;
        /** Folder miniatur z dzisiejszą datą albo null (miniatury wyłączone). */
        private final Path thumbFolder;
        private final List<String> errors = new ArrayList<>();
        private final List<Path> moved = new ArrayList<>();
        private final List<Path> copied = new ArrayList<>();
        private final List<Path> incomplete = new ArrayList<>();
        private final List<Duplicate> duplicates = new ArrayList<>();
        private int waiting;
        private int thumbsCreated;
        private int thumbsExisting;
        private int thumbsUnsupported;
        private long archivedBytes;
        private boolean newArchive;
        private Map<Long, List<Path>> index;
        private FreeSpace archiveSpace;
        private FreeSpace thumbSpace;

        Run(Progress progress, BooleanSupplier cancelled) {
            this.progress = progress;
            this.cancelled = cancelled;
            this.source = options.source().toAbsolutePath().normalize();
            this.target = options.target().toAbsolutePath().normalize();
            String day = today.format(DAY_FORMAT);
            this.dayFolder = target.resolve(day);
            this.thumbFolder = options.makeThumbnails()
                    ? options.thumbnailTarget().toAbsolutePath().normalize().resolve(day) : null;
        }

        Summary execute() {
            progress.update("Wyszukiwanie zdjęć i filmów w folderze archiwizowanym…", -1, 1);
            List<Path> media = collectSource(source, target, errors, cancelled);
            if (media.isEmpty()) {
                // Nic do archiwizacji — archiwum jest tylko pobieżnie sprawdzane (najczęstszy przypadek w tle).
                return summary(dateFolders(target).size());
            }
            progress.update("Sprawdzanie archiwum…", -1, 1);
            newArchive = !Files.isDirectory(target);
            List<Path> folders = openArchive(target);
            progress.update("Indeksowanie archiwum…", -1, 1);
            index = archiveIndex != null ? archiveIndex.current(folders, () -> buildIndex(folders)) : buildIndex(folders);
            archiveSpace = new FreeSpace(target, "archiwum", minFreeBytes);
            thumbSpace = thumbFolder == null ? null : new FreeSpace(thumbFolder, "miniatur", minFreeBytes);

            int total = media.size();
            for (int i = 0; i < total; i++) {
                checkCancelled(cancelled);
                Path file = media.get(i);
                progress.update("Archiwizacja: " + (i + 1) + " z " + total + "  •  " + file.getFileName(), i, total);
                try {
                    archive(file, i, total);
                } catch (IOException | RuntimeException e) {
                    failed(file, e);
                }
            }
            progress.update("Gotowe", total, total);
            return summary(folders.size());
        }

        /**
         * Spis archiwum (rozmiar → pliki). Niepełny spis = przeoczone duplikaty, więc każdy błąd odczytu archiwum
         * przerywa przebieg (i spis nie jest zapamiętywany).
         */
        private Map<Long, List<Path>> buildIndex(List<Path> folders) {
            Map<Long, List<Path>> built = new HashMap<>();
            List<String> indexErrors = new ArrayList<>();
            for (Path folder : folders) {
                indexFolder(folder, built, indexErrors, cancelled);
            }
            if (!indexErrors.isEmpty()) {
                throw new ArchiveUnavailableException("Nie udało się odczytać całego archiwum ("
                        + Texts.count(indexErrors.size(), "błąd", "błędy", "błędów") + ", np. " + indexErrors.get(0)
                        + "). Bez pełnego spisu nie da się wykryć duplikatów.");
            }
            return built;
        }

        /** Jeden plik: czy jest gotowy i kompletny, czy już jest w archiwum, przeniesienie, miniatura. */
        private void archive(Path file, int i, int total) throws IOException {
            FileState state = gate.check(file);
            if (state == FileState.WAIT) {
                waiting++;
                return;
            }
            if (state == FileState.IGNORE) {
                return;
            }
            // Przestał rosnąć, ale czy jest cały? Przerwane przesyłanie zostawia ucięty plik.
            if (MediaIntegrity.check(file) == MediaIntegrity.Result.INCOMPLETE) {
                waiting++;
                incomplete.add(file);
                return;
            }
            long size = Files.size(file);
            Path existing = findInArchive(file, size, index);
            if (existing != null) {
                duplicates.add(new Duplicate(file, existing, size));
                return;
            }
            if (!Locations.sameServer(file, target) && !SafeTransfer.sameStore(file, target)) {
                archiveSpace.require(size); // przeniesienie między dyskami — potrzebne miejsce
            }
            ensureDayFolder(target, dayFolder, newArchive);
            Path destination = SafeTransfer.uniqueName(dayFolder.resolve(file.getFileName().toString()));
            boolean keepOriginal = ProtectedPaths.isProtected(file);
            SafeTransfer.transfer(file, destination, keepOriginal);
            (keepOriginal ? copied : moved).add(destination);
            archivedBytes += size;
            index.computeIfAbsent(size, k -> new ArrayList<>()).add(destination);
            String hash = hashes.remove(file);
            if (hash != null) {
                hashes.put(destination, hash);
            }
            if (thumbFolder != null && isPhoto(destination)) {
                makeThumbnail(destination, i, total);
            }
        }

        /** Miniatura zarchiwizowanego zdjęcia; błąd dotyczy tylko miniatury — zdjęcie jest już w archiwum. */
        private void makeThumbnail(Path photo, int i, int total) {
            if (!ThumbnailGenerator.isJpeg(photo)) {
                thumbsUnsupported++;
                return;
            }
            Path thumbnail = thumbFolder.resolve(photo.getFileName().toString());
            if (Files.exists(thumbnail)) {
                thumbsExisting++;
                return;
            }
            progress.update("Miniatura: " + (i + 1) + " z " + total + "  •  " + photo.getFileName(), i, total);
            thumbSpace.require(0);
            try {
                ThumbnailMaker.make(photo, thumbnail, options.thumbnailSide());
                thumbsCreated++;
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                errors.add(photo + ": nie utworzono miniatury — " + Texts.errorMessage(e));
            }
        }

        /**
         * Błąd przy pliku. Przerwanie, brak miejsca i zniknięcie archiwum (np. zerwane połączenie) kończą
         * przebieg; inne błędy trafiają do listy, a archiwizacja idzie dalej.
         */
        private void failed(Path file, Exception e) {
            if (e instanceof CancelledException cancel) {
                throw cancel;
            }
            if (e instanceof InsufficientSpaceException space) {
                throw new InsufficientSpaceException(space.getMessage() + (archivedSoFar() > 0
                        ? " Wcześniej zarchiwizowano " + archivedSoFarText() + "." : ""));
            }
            errors.add(file + ": " + Texts.errorMessage(e));
            if (!Files.isDirectory(target)) {
                throw new ArchiveUnavailableException("Archiwum przestało być dostępne w trakcie archiwizacji: "
                        + target + (archivedSoFar() > 0 ? " (wcześniej zarchiwizowano " + archivedSoFarText() + ")" : "")
                        + ".");
            }
        }

        private int archivedSoFar() {
            return moved.size() + copied.size();
        }

        private String archivedSoFarText() {
            return Texts.count(archivedSoFar(), "plik", "pliki", "plików");
        }

        private Summary summary(int dateFolderCount) {
            return new Summary(List.copyOf(moved), List.copyOf(copied), List.copyOf(duplicates), dateFolderCount,
                    List.copyOf(errors), archivedBytes, dayFolder, Duration.ofNanos(System.nanoTime() - start),
                    thumbFolder == null ? ThumbnailStats.NONE
                            : new ThumbnailStats(thumbsCreated, thumbsExisting, thumbsUnsupported, thumbFolder),
                    waiting, List.copyOf(incomplete));
        }
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
                    errors.add(file + ": " + Texts.errorMessage(e));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            errors.add(source + ": " + Texts.errorMessage(e));
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
                    if (attrs.isRegularFile() && !file.getFileName().toString().endsWith(SafeTransfer.TMP_SUFFIX)) {
                        index.computeIfAbsent(attrs.size(), k -> new ArrayList<>()).add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    errors.add(file + ": " + Texts.errorMessage(e));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            errors.add(folder + ": " + Texts.errorMessage(e));
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
                if (!Files.exists(candidate) && Files.isDirectory(options.target())) {
                    continue; // usunięty z archiwum od zbudowania spisu
                }
                // Nie wiadomo, czy to duplikat — plik zostaje w źródle do następnego przebiegu.
                throw new IOException("nie udało się odczytać pliku archiwum " + candidate + " (" + Texts.errorMessage(e)
                        + ") — plik zostaje w folderze archiwizowanym", e);
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

    /**
     * Tworzy folder z dzisiejszą datą. Sam folder archiwum jest tworzony tylko wtedy, gdy na starcie był nowy —
     * jeśli zniknął w trakcie (np. zerwane połączenie z dyskiem), nie odtwarzamy go w innym miejscu.
     */
    private static void ensureDayFolder(Path target, Path dayFolder, boolean newArchive) throws IOException {
        if (Files.isDirectory(dayFolder)) {
            return;
        }
        if (!Files.isDirectory(target)) {
            if (!newArchive) {
                throw new IOException("folder archiwum " + target + " jest niedostępny");
            }
            Files.createDirectories(target);
        }
        try {
            Files.createDirectory(dayFolder);
        } catch (FileAlreadyExistsException e) {
            if (!Files.isDirectory(dayFolder)) {
                throw e;
            }
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancelledException();
        }
    }

}
