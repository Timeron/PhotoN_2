package com.photon.remote;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileStoreAttributeView;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pliki jednego połączenia z {@link RemoteServers}. Obiekt istnieje przez cały czas działania programu;
 * zmiana konfiguracji połączenia zamyka tylko otwarte połączenia ({@link #reset()}).
 * <p>
 * Listy katalogów są krótko pamiętane ({@link #LISTING_TTL_NANOS}), bo przechodzenie po drzewie folderów
 * pyta o atrybuty każdego pliku osobno — bez tego każdy plik oznaczałby osobne polecenie do serwera.
 */
final class RemoteFileSystem extends FileSystem {

    static final long LISTING_TTL_NANOS = 5_000_000_000L;

    private record Listing(Map<String, RemoteClient.Entry> entries, long at) {
    }

    private final RemoteFileSystemProvider provider;
    private final String name;
    private volatile ClientPool pool;
    private final Map<String, Listing> listings = new ConcurrentHashMap<>();
    private final RemoteFileStore store;

    RemoteFileSystem(RemoteFileSystemProvider provider, String name) {
        this.provider = provider;
        this.name = name;
        this.pool = new ClientPool(name);
        this.store = new RemoteFileStore(this);
    }

    String name() {
        return name;
    }

    ClientPool pool() {
        return pool;
    }

    /** Zamyka otwarte połączenia (np. po zmianie hasła) i zapomina listy katalogów. */
    void reset() {
        ClientPool old = pool;
        pool = new ClientPool(name);
        old.close();
        listings.clear();
    }

    RemotePath root() {
        return new RemotePath(this, true, new String[0]);
    }

    // =====================================================================
    // Listy katalogów i atrybuty
    // =====================================================================

    /** Zawartość katalogu (z pamięci, jeśli świeża). */
    Map<String, RemoteClient.Entry> list(RemotePath dir) throws IOException {
        String key = dir.toAbsolutePath().remote();
        Listing cached = listings.get(key);
        long now = System.nanoTime();
        if (cached != null && now - cached.at < LISTING_TTL_NANOS) {
            return cached.entries;
        }
        List<RemoteClient.Entry> entries = pool.call(client -> client.list(key));
        Map<String, RemoteClient.Entry> byName = new LinkedHashMap<>();
        for (RemoteClient.Entry entry : entries) {
            byName.put(entry.name(), entry);
        }
        listings.put(key, new Listing(byName, now));
        return byName;
    }

    /** Atrybuty pliku lub folderu; {@link NoSuchFileException}, gdy go nie ma. */
    RemoteClient.Entry stat(RemotePath path) throws IOException {
        RemotePath absolute = (RemotePath) path.toAbsolutePath().normalize();
        String[] names = absolute.names();
        if (names.length == 0) {
            list(absolute); // korzeń „istnieje” tylko, gdy serwer odpowiada
            return new RemoteClient.Entry("/", true, 0, Instant.EPOCH);
        }
        Map<String, RemoteClient.Entry> parent;
        try {
            parent = list((RemotePath) absolute.getParent());
        } catch (NoSuchFileException | NotDirectoryException e) {
            throw new NoSuchFileException(absolute.toString());
        }
        RemoteClient.Entry entry = parent.get(names[names.length - 1]);
        if (entry == null) {
            throw new NoSuchFileException(absolute.toString());
        }
        return entry;
    }

    /** Zapomina listy katalogu, w którym leży {@code path}, i samego {@code path}. */
    void invalidate(RemotePath path) {
        RemotePath absolute = (RemotePath) path.toAbsolutePath().normalize();
        listings.remove(absolute.remote());
        Path parent = absolute.getParent();
        if (parent != null) {
            listings.remove(((RemotePath) parent).remote());
        }
    }

    // =====================================================================
    // FileSystem
    // =====================================================================

    @Override
    public FileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() {
        reset();
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String getSeparator() {
        return "/";
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return List.of(root());
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return List.of(store);
    }

    FileStore store() {
        return store;
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return Set.of("basic");
    }

    @Override
    public Path getPath(String first, String... more) {
        StringBuilder text = new StringBuilder(first);
        for (String part : more) {
            if (!part.isEmpty()) {
                text.append('/').append(part);
            }
        }
        return RemotePath.parse(this, text.toString());
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        PathMatcher local = java.nio.file.FileSystems.getDefault().getPathMatcher(syntaxAndPattern);
        return path -> local.matches(Path.of(((RemotePath) path).remote().replaceFirst("^/", "")));
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException("Serwer zdalny nie powiadamia o zmianach.");
    }

    @Override
    public String toString() {
        return "[" + name + "]";
    }

    /** „Dysk” połączenia — jeden na połączenie (pliki w jego obrębie można przenosić zmianą nazwy). */
    static final class RemoteFileStore extends FileStore {
        private final RemoteFileSystem fs;

        RemoteFileStore(RemoteFileSystem fs) {
            this.fs = fs;
        }

        @Override
        public String name() {
            return fs.name();
        }

        @Override
        public String type() {
            return "remote";
        }

        @Override
        public boolean isReadOnly() {
            return false;
        }

        @Override
        public long getTotalSpace() {
            return 0;
        }

        @Override
        public long getUsableSpace() {
            return 0;
        }

        @Override
        public long getUnallocatedSpace() {
            return 0;
        }

        @Override
        public boolean supportsFileAttributeView(Class<? extends java.nio.file.attribute.FileAttributeView> type) {
            return type == java.nio.file.attribute.BasicFileAttributeView.class;
        }

        @Override
        public boolean supportsFileAttributeView(String name) {
            return "basic".equals(name);
        }

        @Override
        public <V extends FileStoreAttributeView> V getFileStoreAttributeView(Class<V> type) {
            return null;
        }

        @Override
        public Object getAttribute(String attribute) {
            throw new UnsupportedOperationException(attribute);
        }
    }
}
