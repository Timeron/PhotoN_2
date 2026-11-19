package com.photon.duplicates.scan;

import com.photon.duplicates.DuplicateSearchOptions;
import com.photon.duplicates.KeepStrategy;
import com.photon.duplicates.SearchMethod;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileVisitOption;
import java.nio.file.NoSuchFileException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import com.photon.CancelledException;

/**
 * Silnik wyszukiwania duplikatów: przegląda lokalizacje według konfiguracji, porównuje pliki
 * wybraną metodą i zwraca grupy duplikatów z wyznaczonym oryginałem.
 * Nie zależy od JavaFX — postęp zgłasza przez {@link Progress}.
 */
public class DuplicateScanner {

    /** Odbiorca postępu. {@code total < 0} oznacza postęp nieokreślony. */
    @FunctionalInterface
    public interface Progress {
        void update(String message, long done, long total);
    }


    private static final int MAX_ERROR_MESSAGES = 200;

    private final DuplicateSearchOptions options;
    private final HashCache cache;

    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger errorCount = new AtomicInteger();
    private int skippedFiles;

    /**
     * @param cache pamięć podręczna odcisków albo {@code null}, gdy wyłączona
     */
    public DuplicateScanner(DuplicateSearchOptions options, HashCache cache) {
        this.options = Objects.requireNonNull(options);
        this.cache = cache;
    }

    public ScanResult scan(Progress progress, BooleanSupplier cancelled) {
        Instant start = Instant.now();
        List<ScannedFile> files = collectFiles(progress, cancelled);

        List<List<ScannedFile>> clusters = switch (options.method() == null ? SearchMethod.EXACT : options.method()) {
            case EXACT -> groupExact(files, progress, cancelled);
            case NAME_SIZE -> groupByKey(files, f -> f.fileName().toLowerCase(Locale.ROOT) + "|" + f.size());
            case SIMILAR -> groupSimilar(files, progress, cancelled);
            case METADATA -> groupByMetadata(files, progress, cancelled);
        };

        List<DuplicateGroup> groups = new ArrayList<>();
        for (List<ScannedFile> cluster : clusters) {
            if (cluster.size() < 2) {
                continue;
            }
            if (options.crossLocationOnly()
                    && cluster.stream().map(ScannedFile::locationIndex).distinct().count() < 2) {
                continue;
            }
            groups.add(new DuplicateGroup(orderWithOriginalFirst(cluster, options.keepStrategy())));
        }
        groups.sort(Comparator.comparingLong(DuplicateGroup::reclaimableSize).reversed()
                .thenComparing(DuplicateGroup::name, String.CASE_INSENSITIVE_ORDER));

        if (cache != null) {
            try {
                cache.save();
            } catch (IOException e) {
                addError("Nie udało się zapisać pamięci podręcznej: " + e.getMessage());
            }
        }

        long bytes = files.stream().mapToLong(ScannedFile::size).sum();
        return new ScanResult(options, groups, files.size(), bytes, skippedFiles,
                List.copyOf(errors), errorCount.get(), Duration.between(start, Instant.now()), Instant.now());
    }

    // =====================================================================
    // Etap 1: wyszukiwanie plików
    // =====================================================================

    private List<ScannedFile> collectFiles(Progress progress, BooleanSupplier cancelled) {
        List<ScannedFile> result = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        Set<Path> exclusions = new HashSet<>();
        for (Path p : nullSafe(options.exclusions())) {
            exclusions.add(normalize(p));
        }
        Set<String> extensions = options.extensions() == null ? Set.of() : options.extensions();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate from = options.modifiedFrom();
        LocalDate to = options.modifiedTo();
        int maxDepth = options.includeSubfolders() ? Integer.MAX_VALUE : 1;

        List<Path> locations = nullSafe(options.locations());
        for (int index = 0; index < locations.size(); index++) {
            Path root = normalize(locations.get(index));
            int locationIndex = index;
            if (!Files.isDirectory(root)) {
                addError("Lokalizacja nie istnieje lub nie jest folderem: " + root);
                continue;
            }
            try {
                Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), maxDepth,
                        new FileVisitor<>() {
                            @Override
                            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                                checkCancelled(cancelled);
                                if (dir.equals(root)) {
                                    return FileVisitResult.CONTINUE;
                                }
                                if (isExcluded(dir, exclusions)
                                        || (options.skipHidden() && isHidden(dir, attrs))
                                        || (options.skipSymlinks() && attrs.isSymbolicLink())) {
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                                checkCancelled(cancelled);
                                BasicFileAttributes a = attrs;
                                if (a.isSymbolicLink()) {
                                    if (options.skipSymlinks()) {
                                        return FileVisitResult.CONTINUE;
                                    }
                                    try {
                                        a = Files.readAttributes(file, BasicFileAttributes.class);
                                    } catch (IOException e) {
                                        addError(file + ": " + e.getMessage());
                                        return FileVisitResult.CONTINUE;
                                    }
                                }
                                if (!a.isRegularFile()) {
                                    return FileVisitResult.CONTINUE;
                                }
                                String ext = ScannedFile.extensionOf(file.getFileName().toString());
                                if (!extensions.contains(ext)) {
                                    return FileVisitResult.CONTINUE;
                                }
                                if (options.skipHidden() && isHidden(file, attrs)) {
                                    return FileVisitResult.CONTINUE;
                                }
                                long size = a.size();
                                if (size < options.minSizeBytes()
                                        || (options.maxSizeBytes() > 0 && size > options.maxSizeBytes())) {
                                    return FileVisitResult.CONTINUE;
                                }
                                Instant modified = a.lastModifiedTime().toInstant();
                                LocalDate day = modified.atZone(zone).toLocalDate();
                                if ((from != null && day.isBefore(from)) || (to != null && day.isAfter(to))) {
                                    return FileVisitResult.CONTINUE;
                                }
                                Path normalized = file.toAbsolutePath().normalize();
                                if (!seen.add(normalized)) {
                                    return FileVisitResult.CONTINUE; // nakładające się lokalizacje
                                }
                                Instant created = a.creationTime() == null ? modified : a.creationTime().toInstant();
                                result.add(new ScannedFile(normalized, size, modified, created, locationIndex));
                                if (result.size() % 200 == 0) {
                                    progress.update("Wyszukiwanie plików… znaleziono " + result.size(), 0, -1);
                                }
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                                addError(file + ": " + describe(exc));
                                return FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                                if (exc != null) {
                                    addError(dir + ": " + describe(exc));
                                }
                                return FileVisitResult.CONTINUE;
                            }
                        });
            } catch (IOException e) {
                addError(root + ": " + describe(e));
            }
        }
        progress.update("Znaleziono " + result.size() + " plików do porównania", 0, -1);
        return result;
    }

    private static boolean isExcluded(Path dir, Set<Path> exclusions) {
        Path normalized = dir.toAbsolutePath().normalize();
        for (Path excluded : exclusions) {
            if (normalized.startsWith(excluded)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHidden(Path path, BasicFileAttributes attrs) {
        Path name = path.getFileName();
        if (name != null && name.toString().startsWith(".")) {
            return true;
        }
        if (attrs instanceof DosFileAttributes dos) {
            return dos.isHidden() || dos.isSystem();
        }
        try {
            DosFileAttributes dos = Files.readAttributes(path, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return dos.isHidden() || dos.isSystem();
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    // =====================================================================
    // Etap 2: porównywanie
    // =====================================================================

    /** Identyczne pliki: najpierw ten sam rozmiar, potem SHA-256. */
    private List<List<ScannedFile>> groupExact(List<ScannedFile> files, Progress progress, BooleanSupplier cancelled) {
        List<ScannedFile> candidates = groupByKey(files, ScannedFile::size).stream()
                .flatMap(List::stream)
                .toList();
        Map<ScannedFile, String> hashes = computeAll(candidates, "Obliczanie sum kontrolnych", progress, cancelled,
                file -> {
                    HashCache.Entry entry = cached(file);
                    if (entry.sha256() != null) {
                        return entry.sha256();
                    }
                    String hash = Fingerprints.sha256(file.path());
                    store(file, entry.withSha256(hash));
                    return hash;
                });
        return groupByKey(candidates.stream().filter(hashes::containsKey).toList(),
                f -> f.size() + "|" + hashes.get(f));
    }

    /** Podobne obrazy: hash percepcyjny i odległość Hamminga. */
    private List<List<ScannedFile>> groupSimilar(List<ScannedFile> files, Progress progress, BooleanSupplier cancelled) {
        List<ScannedFile> images = files.stream()
                .filter(f -> Fingerprints.DECODABLE_IMAGES.contains(f.extension()))
                .toList();
        skippedFiles += files.size() - images.size();

        Map<ScannedFile, Long> hashes = computeAll(images, "Analiza obrazów", progress, cancelled, file -> {
            HashCache.Entry entry = cached(file);
            if (entry.imageHash() != null) {
                return entry.imageHash();
            }
            long hash = Fingerprints.imageHash(file.path());
            store(file, entry.withImageHash(hash));
            return hash;
        });

        List<ScannedFile> hashed = images.stream().filter(hashes::containsKey).toList();
        int threshold = options.similarityThreshold() <= 0 ? 90 : options.similarityThreshold();
        int maxDistance = (int) Math.floor((100 - threshold) * 64 / 100.0);

        int n = hashed.size();
        long[] values = new long[n];
        for (int i = 0; i < n; i++) {
            values[i] = hashes.get(hashed.get(i));
        }
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            if (i % 500 == 0) {
                checkCancelled(cancelled);
                progress.update("Porównywanie obrazów… " + i + " z " + n, i, n);
            }
            for (int j = i + 1; j < n; j++) {
                if (Long.bitCount(values[i] ^ values[j]) <= maxDistance) {
                    union(parent, i, j);
                }
            }
        }
        Map<Integer, List<ScannedFile>> clusters = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            clusters.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(hashed.get(i));
        }
        return new ArrayList<>(clusters.values());
    }

    /** Ta sama data wykonania, aparat i rozdzielczość z metadanych. */
    private List<List<ScannedFile>> groupByMetadata(List<ScannedFile> files, Progress progress,
                                                    BooleanSupplier cancelled) {
        Map<ScannedFile, String> keys = computeAll(files, "Odczyt metadanych", progress, cancelled, file -> {
            HashCache.Entry entry = cached(file);
            if (entry.metadataKey() != null) {
                return entry.metadataKey();
            }
            String key = Objects.requireNonNullElse(Fingerprints.metadataKey(file.path()), "");
            store(file, entry.withMetadataKey(key));
            return key;
        });
        List<ScannedFile> withKey = files.stream()
                .filter(f -> keys.containsKey(f) && !keys.get(f).isEmpty())
                .toList();
        skippedFiles += files.size() - withKey.size();
        return groupByKey(withKey, keys::get);
    }

    @FunctionalInterface
    private interface FileFunction<T> {
        T apply(ScannedFile file) throws IOException;
    }

    /** Liczy wartość dla każdego pliku równolegle; pliki z błędem odczytu są pomijane. */
    private <T> Map<ScannedFile, T> computeAll(List<ScannedFile> files, String label, Progress progress,
                                               BooleanSupplier cancelled, FileFunction<T> function) {
        Map<ScannedFile, T> result = new ConcurrentHashMap<>();
        if (files.isEmpty()) {
            return result;
        }
        int total = files.size();
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, options.threads()), r -> {
            Thread t = new Thread(r, "photon-scan");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>(total);
            for (ScannedFile file : files) {
                futures.add(pool.submit(() -> {
                    if (cancelled.getAsBoolean()) {
                        return;
                    }
                    try {
                        T value = function.apply(file);
                        if (value != null) {
                            result.put(file, value);
                        }
                    } catch (IOException | RuntimeException e) {
                        addError(file.path() + ": " + describe(e));
                    }
                    int d = done.incrementAndGet();
                    if (d % 10 == 0 || d == total) {
                        progress.update(label + "… " + d + " z " + total, d, total);
                    }
                }));
            }
            for (Future<?> future : futures) {
                checkCancelled(cancelled);
                try {
                    future.get();
                } catch (ExecutionException e) {
                    addError(describe(e.getCause()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancelledException();
                }
            }
        } finally {
            pool.shutdownNow();
        }
        checkCancelled(cancelled);
        return result;
    }

    private HashCache.Entry cached(ScannedFile file) {
        return cache != null && options.useHashCache() ? cache.get(file) : HashCache.Entry.empty(file);
    }

    private void store(ScannedFile file, HashCache.Entry entry) {
        if (cache != null && options.useHashCache()) {
            cache.put(file, entry);
        }
    }

    private static <K> List<List<ScannedFile>> groupByKey(Collection<ScannedFile> files,
                                                          Function<ScannedFile, K> key) {
        Map<K, List<ScannedFile>> map = new HashMap<>();
        for (ScannedFile file : files) {
            map.computeIfAbsent(key.apply(file), k -> new ArrayList<>()).add(file);
        }
        return map.values().stream().filter(list -> list.size() > 1).toList();
    }

    // =====================================================================
    // Wybór oryginału
    // =====================================================================

    /** Ustawia oryginał (wg strategii) na początku, pozostałe pliki alfabetycznie po ścieżce. */
    static List<ScannedFile> orderWithOriginalFirst(List<ScannedFile> files, KeepStrategy strategy) {
        Comparator<ScannedFile> byPath = Comparator.comparing(f -> f.path().toString(), String.CASE_INSENSITIVE_ORDER);
        Comparator<ScannedFile> shortest = Comparator.<ScannedFile>comparingInt(f -> f.path().toString().length())
                .thenComparing(byPath);
        Comparator<ScannedFile> preference = switch (strategy == null ? KeepStrategy.OLDEST : strategy) {
            case OLDEST -> Comparator.comparing(ScannedFile::earliestDate).thenComparing(shortest);
            case NEWEST -> Comparator.comparing(ScannedFile::modified).reversed().thenComparing(shortest);
            case HIGHEST_QUALITY -> Comparator.comparingLong(ScannedFile::size).reversed().thenComparing(shortest);
            case LOCATION_PRIORITY -> Comparator.comparingInt(ScannedFile::locationIndex).thenComparing(shortest);
            case SHORTEST_PATH -> shortest;
        };
        ScannedFile original = files.stream().min(preference).orElseThrow();
        List<ScannedFile> ordered = new ArrayList<>(files.size());
        ordered.add(original);
        files.stream().filter(f -> f != original).sorted(byPath).forEach(ordered::add);
        return ordered;
    }

    // =====================================================================
    // Pomocnicze
    // =====================================================================

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancelledException();
        }
    }

    private void addError(String message) {
        if (errorCount.incrementAndGet() <= MAX_ERROR_MESSAGES) {
            errors.add(message);
        }
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        String type = e.getClass().getSimpleName();
        if (e instanceof AccessDeniedException) {
            return "brak dostępu";
        }
        if (e instanceof NoSuchFileException) {
            return "plik nie istnieje";
        }
        return message == null || message.isBlank() ? type : message;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static Path normalize(Path p) {
        return p.toAbsolutePath().normalize();
    }
}
