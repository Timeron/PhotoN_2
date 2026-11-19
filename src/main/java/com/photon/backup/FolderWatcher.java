package com.photon.backup;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Natychmiastowe powiadomienia o nowych plikach w folderze (w Windows przez systemowe powiadomienia o zmianach).
 * Reaguje na pojawienie się pliku lub folderu (także po zmianie nazwy, np. {@code IMG.jpg.part → IMG.jpg});
 * zmiany zawartości nie budzą agenta — tym zajmuje się cykliczne sprawdzanie.
 * <p>
 * Powiadomienia bywają zawodne (dyski sieciowe, telefon podłączony jako urządzenie), dlatego są tylko
 * dodatkiem do sprawdzania cyklicznego. Gdy folder zniknie, obserwator przestaje być aktywny ({@link #isActive()}).
 */
public class FolderWatcher implements Closeable {

    private final Path root;
    private final boolean recursive;
    private final Path excluded;
    private final Runnable onEvent;
    private final WatchService service;
    private final Map<WatchKey, Path> keys = new ConcurrentHashMap<>();
    private final Thread thread;
    private volatile boolean active = true;
    private volatile boolean closed;

    /**
     * @param excluded folder pomijany (np. archiwum leżące wewnątrz folderu źródłowego) albo null
     */
    public FolderWatcher(Path root, boolean recursive, Path excluded, Runnable onEvent) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.recursive = recursive;
        this.excluded = excluded == null ? null : excluded.toAbsolutePath().normalize();
        this.onEvent = onEvent;
        this.service = FileSystems.getDefault().newWatchService();
        try {
            register(this.root);
        } catch (IOException e) {
            service.close();
            throw e;
        }
        thread = new Thread(this::loop, "photon-folder-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    public boolean isActive() {
        return active;
    }

    public Path root() {
        return root;
    }

    private void register(Path dir) throws IOException {
        if (!recursive) {
            keys.put(dir.register(service, StandardWatchEventKinds.ENTRY_CREATE), dir);
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                if (excluded != null && d.toAbsolutePath().normalize().equals(excluded)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                keys.put(d.register(service, StandardWatchEventKinds.ENTRY_CREATE), d);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void loop() {
        try {
            while (true) {
                WatchKey key = service.take();
                Path dir = keys.get(key);
                boolean signal = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        signal = true;
                        continue;
                    }
                    signal = true;
                    if (recursive && dir != null && event.context() instanceof Path name) {
                        Path child = dir.resolve(name);
                        if (Files.isDirectory(child)) {
                            try {
                                register(child);
                            } catch (IOException e) {
                                // folder zniknął od razu — pomijamy
                            }
                        }
                    }
                }
                if (!key.reset()) {
                    keys.remove(key);
                    if (root.equals(dir)) {
                        active = false; // folder źródłowy zniknął (np. odłączony dysk)
                        onEvent.run();
                        return;
                    }
                }
                if (signal) {
                    onEvent.run();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException e) {
            // zamknięty
        } finally {
            boolean wasActive = active;
            active = false;
            if (wasActive && !closed) {
                onEvent.run(); // obserwacja przestała działać — agent musi się o tym dowiedzieć (inaczej czekałby bez końca)
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        active = false;
        try {
            service.close();
        } catch (IOException e) {
            // ignorujemy
        }
    }
}
