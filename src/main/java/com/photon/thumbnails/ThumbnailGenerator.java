package com.photon.thumbnails;

import com.photon.duplicates.MediaFormat;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Tworzy miniatury wszystkich zdjęć JPEG z folderu źródłowego (równolegle).
 */
public final class ThumbnailGenerator {

    /** Pamięć potrzebna na jedno duże zdjęcie w trakcie przetwarzania (szacunkowo). */
    private static final long MEMORY_PER_THREAD = 700L * 1024 * 1024;

    public interface Progress {
        void update(String message, long done, long total);
    }

    public static final class CancelledException extends RuntimeException {
        public CancelledException() {
            super("Przerwano");
        }
    }

    /**
     * @param resized          zmniejszone zdjęcia
     * @param keptSize         zdjęcia już mniejsze niż zadany rozmiar (skopiowane bez ponownego kodowania)
     * @param skippedExisting  pominięte, bo miniatura już istnieje
     * @param unsupported      zdjęcia w formatach innych niż JPEG (pominięte)
     * @param errors           błędy (ścieżka: opis)
     * @param sourceBytes      łączny rozmiar przetworzonych oryginałów
     * @param thumbnailBytes   łączny rozmiar utworzonych miniatur
     */
    public record Summary(int resized, int keptSize, int skippedExisting, int unsupported, List<String> errors,
                          long sourceBytes, long thumbnailBytes, Duration elapsed) {
        public int created() {
            return resized + keptSize;
        }
    }

    private final ThumbnailOptions options;
    private final int threads;

    public ThumbnailGenerator(ThumbnailOptions options, int threads) {
        this.options = options;
        long byMemory = Math.max(1, Runtime.getRuntime().maxMemory() / MEMORY_PER_THREAD);
        this.threads = (int) Math.max(1, Math.min(threads, byMemory));
    }

    public static boolean isJpeg(Path file) {
        return MediaFormat.forExtension(extension(file)).map(f -> f == MediaFormat.JPEG).orElse(false);
    }

    public Summary run(Progress progress, BooleanSupplier cancelled) {
        long start = System.nanoTime();
        String problem = options.validate();
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        Path source = options.source().toAbsolutePath().normalize();
        Path target = options.target().toAbsolutePath().normalize();
        boolean targetInsideSource = target.startsWith(source);

        progress.update("Wyszukiwanie zdjęć…", 0, -1);
        List<Path> jpegs = new ArrayList<>();
        AtomicInteger unsupported = new AtomicInteger();
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        collect(source, target, jpegs, unsupported, errors, cancelled);

        AtomicInteger resized = new AtomicInteger();
        AtomicInteger kept = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        AtomicLong sourceBytes = new AtomicLong();
        AtomicLong thumbnailBytes = new AtomicLong();
        int total = jpegs.size();
        progress.update("Tworzenie miniatur: 0 z " + total, 0, total);

        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "photon-thumbnail");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Path file : jpegs) {
                futures.add(pool.submit(() -> {
                    if (cancelled.getAsBoolean()) {
                        return;
                    }
                    Path out = target.resolve(source.relativize(file).toString()).normalize();
                    try {
                        // Miniatura nigdy nie trafia między oryginały (np. gdy źródło leży w folderze docelowym).
                        if (out.startsWith(source) && !targetInsideSource) {
                            throw new IOException("miniatura trafiłaby do folderu ze zdjęciami ("
                                    + out + ") — pominięto, aby nie zastąpić oryginału");
                        }
                        if (Files.exists(out)) {
                            if (!options.overwrite()) {
                                skipped.incrementAndGet();
                                return;
                            }
                        }
                        ThumbnailMaker.Result result = ThumbnailMaker.make(file, out, options.maxSide());
                        (result == ThumbnailMaker.Result.RESIZED ? resized : kept).incrementAndGet();
                        sourceBytes.addAndGet(Files.size(file));
                        thumbnailBytes.addAndGet(Files.size(out));
                    } catch (IOException | RuntimeException | OutOfMemoryError e) {
                        errors.add(file + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                    } finally {
                        int n = done.incrementAndGet();
                        progress.update("Tworzenie miniatur: " + n + " z " + total + "  •  " + file.getFileName(),
                                n, total);
                    }
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancelledException();
                } catch (java.util.concurrent.ExecutionException e) {
                    errors.add(String.valueOf(e.getCause()));
                }
            }
        } finally {
            pool.shutdownNow();
        }
        if (cancelled.getAsBoolean()) {
            throw new CancelledException();
        }
        return new Summary(resized.get(), kept.get(), skipped.get(), unsupported.get(), List.copyOf(errors),
                sourceBytes.get(), thumbnailBytes.get(), Duration.ofNanos(System.nanoTime() - start));
    }

    private void collect(Path source, Path target, List<Path> jpegs, AtomicInteger unsupported,
                         List<String> errors, BooleanSupplier cancelled) {
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (cancelled.getAsBoolean()) {
                        throw new CancelledException();
                    }
                    if (dir.equals(target)) {
                        return FileVisitResult.SKIP_SUBTREE; // miniatury w podfolderze źródła
                    }
                    if (!dir.equals(source) && !options.includeSubfolders()) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (isJpeg(file)) {
                        jpegs.add(file);
                    } else if (MediaFormat.forExtension(extension(file))
                            .map(f -> f.getKind() == MediaFormat.Kind.PHOTO).orElse(false)) {
                        unsupported.incrementAndGet();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    errors.add(file + ": " + e.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            errors.add(source + ": " + e.getMessage());
        }
        Collections.sort(jpegs);
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
