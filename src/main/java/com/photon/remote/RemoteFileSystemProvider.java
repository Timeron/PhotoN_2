package com.photon.remote;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pliki na serwerach FTP/FTPS/SFTP widziane jak zwykłe ścieżki {@link Path} — dzięki temu archiwizacja,
 * wyszukiwanie duplikatów i miniaturyzacja działają na nich tym samym kodem co na dysku lokalnym.
 * Ścieżki tworzy {@link Locations}.
 */
public final class RemoteFileSystemProvider extends FileSystemProvider {

    public static final String SCHEME = "photon-remote";

    private static final RemoteFileSystemProvider INSTANCE = new RemoteFileSystemProvider();

    private final Map<String, RemoteFileSystem> systems = new ConcurrentHashMap<>();

    private RemoteFileSystemProvider() {
    }

    public static RemoteFileSystemProvider instance() {
        return INSTANCE;
    }

    /** System plików połączenia o tej nazwie (bez łączenia się — połączenie powstaje przy pierwszej operacji). */
    RemoteFileSystem fileSystem(String name) {
        String canonical = RemoteServers.find(name).map(RemoteServer::name).orElse(name.trim());
        return systems.computeIfAbsent(canonical.toLowerCase(Locale.ROOT), k -> new RemoteFileSystem(this, canonical));
    }

    /** Zamyka otwarte połączenia z serwerem (np. po zmianie jego konfiguracji). */
    void close(String name) {
        RemoteFileSystem fs = systems.get(name.toLowerCase(Locale.ROOT));
        if (fs != null) {
            fs.reset();
        }
    }

    /** Zamyka wszystkie otwarte połączenia (np. przy zamykaniu programu). */
    public void closeAll() {
        systems.values().forEach(RemoteFileSystem::reset);
    }

    private static RemotePath remote(Path path) {
        if (path instanceof RemotePath remote) {
            return remote;
        }
        throw new ProviderMismatchException(String.valueOf(path));
    }

    // =====================================================================
    // Systemy plików
    // =====================================================================

    @Override
    public String getScheme() {
        return SCHEME;
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) {
        return fileSystem(uri.getAuthority());
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        return fileSystem(uri.getAuthority());
    }

    @Override
    public Path getPath(URI uri) {
        return fileSystem(uri.getAuthority()).getPath(uri.getPath() == null ? "/" : uri.getPath());
    }

    // =====================================================================
    // Odczyt i zapis
    // =====================================================================

    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        RemotePath p = remote(path);
        for (OpenOption option : options) {
            if (option == StandardOpenOption.WRITE || option == StandardOpenOption.APPEND) {
                throw new UnsupportedOperationException(option.toString());
            }
        }
        ClientPool pool = p.getFileSystem().pool();
        RemoteClient client = pool.borrowClient();
        InputStream in;
        try {
            in = client.read(p.toAbsolutePath().remote());
        } catch (IOException | RuntimeException e) {
            pool.release(client, ClientPool.isLogical(e));
            throw e;
        }
        return new FilterInputStream(in) {
            private boolean closed;

            @Override
            public void close() throws IOException {
                if (closed) {
                    return;
                }
                closed = true;
                boolean ok = false;
                try {
                    super.close();
                    ok = true;
                } finally {
                    pool.release(client, ok);
                }
            }
        };
    }

    @Override
    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        RemotePath p = remote(path);
        RemoteFileSystem fs = p.getFileSystem();
        Set<OpenOption> set = Set.copyOf(Arrays.asList(options));
        if (set.contains(StandardOpenOption.APPEND)) {
            throw new UnsupportedOperationException("Dopisywanie do pliku na serwerze nie jest obsługiwane.");
        }
        boolean createNew = set.contains(StandardOpenOption.CREATE_NEW);
        boolean create = set.isEmpty() || createNew || set.contains(StandardOpenOption.CREATE);
        boolean exists = exists(p);
        if (createNew && exists) {
            throw new FileAlreadyExistsException(p.toString());
        }
        if (!create && !exists) {
            throw new NoSuchFileException(p.toString());
        }
        ClientPool pool = fs.pool();
        RemoteClient client = pool.borrowClient();
        OutputStream out;
        try {
            out = client.write(p.toAbsolutePath().remote());
        } catch (IOException | RuntimeException e) {
            pool.release(client, ClientPool.isLogical(e));
            throw e;
        }
        fs.invalidate(p);
        return new FilterOutputStream(out) {
            private boolean closed;

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void close() throws IOException {
                if (closed) {
                    return;
                }
                closed = true;
                boolean ok = false;
                try {
                    super.close();
                    ok = true;
                } finally {
                    pool.release(client, ok);
                    fs.invalidate(p);
                }
            }
        };
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                              FileAttribute<?>... attrs) throws IOException {
        if (options.contains(StandardOpenOption.WRITE) || options.contains(StandardOpenOption.APPEND)) {
            List<OpenOption> outOptions = new ArrayList<>(options);
            outOptions.remove(StandardOpenOption.READ);
            outOptions.remove(StandardOpenOption.WRITE);
            if (outOptions.isEmpty()) {
                outOptions.add(StandardOpenOption.TRUNCATE_EXISTING); // WRITE bez CREATE — plik musi istnieć
            }
            return new WriteChannel(newOutputStream(path, outOptions.toArray(OpenOption[]::new)));
        }
        long size = remote(path).getFileSystem().stat(remote(path)).size();
        return new ReadChannel(newInputStream(path), size);
    }

    // =====================================================================
    // Foldery, usuwanie, kopiowanie, przenoszenie
    // =====================================================================

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter)
            throws IOException {
        RemotePath d = remote(dir);
        RemoteFileSystem fs = d.getFileSystem();
        if (!fs.stat(d).directory()) {
            throw new NotDirectoryException(d.toString());
        }
        List<Path> paths = new ArrayList<>();
        for (String name : fs.list(d).keySet()) {
            Path child = d.resolve(name);
            if (filter == null || filter.accept(child)) {
                paths.add(child);
            }
        }
        return new DirectoryStream<>() {
            @Override
            public Iterator<Path> iterator() {
                return paths.iterator();
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        RemotePath d = remote(dir);
        RemoteFileSystem fs = d.getFileSystem();
        if (exists(d)) {
            throw new FileAlreadyExistsException(d.toString());
        }
        String target = d.toAbsolutePath().remote();
        fs.pool().call(client -> {
            client.mkdir(target);
            return null;
        });
        fs.invalidate(d);
    }

    @Override
    public void delete(Path path) throws IOException {
        RemotePath p = remote(path);
        RemoteFileSystem fs = p.getFileSystem();
        RemoteClient.Entry entry = fs.stat(p);
        String target = p.toAbsolutePath().remote();
        if (entry.directory()) {
            if (!fs.list(p).isEmpty()) {
                throw new DirectoryNotEmptyException(p.toString());
            }
            fs.pool().call(client -> {
                client.deleteDirectory(target);
                return null;
            });
        } else {
            fs.pool().call(client -> {
                client.deleteFile(target);
                return null;
            });
        }
        fs.invalidate(p);
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        RemotePath from = remote(source);
        RemotePath to = remote(target);
        List<CopyOption> list = Arrays.asList(options);
        if (from.toAbsolutePath().normalize().equals(to.toAbsolutePath().normalize())) {
            return;
        }
        RemoteClient.Entry entry = from.getFileSystem().stat(from);
        prepareTarget(to, list.contains(StandardCopyOption.REPLACE_EXISTING));
        if (entry.directory()) {
            createDirectory(to);
        } else {
            try (InputStream in = newInputStream(from); OutputStream out = newOutputStream(to)) {
                in.transferTo(out);
            }
        }
        if (list.contains(StandardCopyOption.COPY_ATTRIBUTES)) {
            setModifiedQuietly(to, entry.modified());
        }
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        RemotePath from = remote(source);
        RemotePath to = remote(target);
        List<CopyOption> list = Arrays.asList(options);
        if (from.toAbsolutePath().normalize().equals(to.toAbsolutePath().normalize())) {
            return;
        }
        RemoteFileSystem fs = from.getFileSystem();
        if (fs != to.getFileSystem()) {
            if (list.contains(StandardCopyOption.ATOMIC_MOVE)) {
                throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "różne serwery");
            }
            List<CopyOption> copyOptions = new ArrayList<>(list);
            copyOptions.add(StandardCopyOption.COPY_ATTRIBUTES);
            copy(from, to, copyOptions.toArray(CopyOption[]::new));
            delete(from);
            return;
        }
        fs.stat(from); // NoSuchFileException, gdy nie ma czego przenosić
        prepareTarget(to, list.contains(StandardCopyOption.REPLACE_EXISTING));
        String a = from.toAbsolutePath().remote();
        String b = to.toAbsolutePath().remote();
        fs.pool().call(client -> {
            client.rename(a, b);
            return null;
        });
        fs.invalidate(from);
        fs.invalidate(to);
    }

    private void prepareTarget(RemotePath target, boolean replace) throws IOException {
        if (exists(target)) {
            if (!replace) {
                throw new FileAlreadyExistsException(target.toString());
            }
            delete(target);
        }
    }

    private static boolean exists(RemotePath path) throws IOException {
        try {
            path.getFileSystem().stat(path);
            return true;
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    private static void setModifiedQuietly(RemotePath path, java.time.Instant time) {
        if (time == null || time.equals(java.time.Instant.EPOCH)) {
            return;
        }
        String target = path.toAbsolutePath().remote();
        try {
            path.getFileSystem().pool().call(client -> client.setModified(target, time));
        } catch (IOException e) {
            // data modyfikacji to tylko dodatek — plik jest już zapisany
        }
        path.getFileSystem().invalidate(path);
    }

    // =====================================================================
    // Atrybuty
    // =====================================================================

    @Override
    public boolean isSameFile(Path path, Path path2) {
        return path.toAbsolutePath().normalize().equals(path2.toAbsolutePath().normalize());
    }

    @Override
    public boolean isHidden(Path path) {
        Path name = path.getFileName();
        return name != null && name.toString().startsWith(".");
    }

    @Override
    public FileStore getFileStore(Path path) {
        return remote(path).getFileSystem().store();
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        RemotePath p = remote(path);
        p.getFileSystem().stat(p);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        if (type != BasicFileAttributeView.class) {
            return null;
        }
        RemotePath p = remote(path);
        return (V) new BasicFileAttributeView() {
            @Override
            public String name() {
                return "basic";
            }

            @Override
            public BasicFileAttributes readAttributes() throws IOException {
                return new Attributes(p.getFileSystem().stat(p));
            }

            @Override
            public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) {
                if (lastModifiedTime != null) {
                    setModifiedQuietly(p, lastModifiedTime.toInstant());
                }
            }
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
            throws IOException {
        if (type != BasicFileAttributes.class) {
            throw new UnsupportedOperationException("Atrybuty " + type.getSimpleName() + " niedostępne na serwerze.");
        }
        RemotePath p = remote(path);
        return (A) new Attributes(p.getFileSystem().stat(p));
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        String names = attributes.startsWith("basic:") ? attributes.substring(6) : attributes;
        if (attributes.contains(":") && !attributes.startsWith("basic:")) {
            throw new UnsupportedOperationException(attributes);
        }
        BasicFileAttributes a = readAttributes(path, BasicFileAttributes.class);
        Map<String, Object> all = new HashMap<>();
        all.put("lastModifiedTime", a.lastModifiedTime());
        all.put("lastAccessTime", a.lastAccessTime());
        all.put("creationTime", a.creationTime());
        all.put("size", a.size());
        all.put("isRegularFile", a.isRegularFile());
        all.put("isDirectory", a.isDirectory());
        all.put("isSymbolicLink", false);
        all.put("isOther", false);
        all.put("fileKey", null);
        if (names.equals("*")) {
            return all;
        }
        Map<String, Object> selected = new HashMap<>();
        for (String name : names.split(",")) {
            if (all.containsKey(name)) {
                selected.put(name, all.get(name));
            }
        }
        return selected;
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
        String name = attribute.startsWith("basic:") ? attribute.substring(6) : attribute;
        if (name.equals("lastModifiedTime") && value instanceof FileTime time) {
            setModifiedQuietly(remote(path), time.toInstant());
            return;
        }
        throw new UnsupportedOperationException(attribute);
    }

    /** Atrybuty z listy katalogu. */
    private record Attributes(RemoteClient.Entry entry) implements BasicFileAttributes {
        @Override
        public FileTime lastModifiedTime() {
            return FileTime.from(entry.modified());
        }

        @Override
        public FileTime lastAccessTime() {
            return lastModifiedTime();
        }

        @Override
        public FileTime creationTime() {
            return lastModifiedTime();
        }

        @Override
        public boolean isRegularFile() {
            return !entry.directory();
        }

        @Override
        public boolean isDirectory() {
            return entry.directory();
        }

        @Override
        public boolean isSymbolicLink() {
            return false;
        }

        @Override
        public boolean isOther() {
            return false;
        }

        @Override
        public long size() {
            return entry.size();
        }

        @Override
        public Object fileKey() {
            return null;
        }
    }

    // =====================================================================
    // Kanały (Files.readAllBytes, Files.createFile…)
    // =====================================================================

    private static final class ReadChannel implements SeekableByteChannel {
        private final InputStream in;
        private final long size;
        private long position;
        private boolean open = true;

        ReadChannel(InputStream in, long size) {
            this.in = in;
            this.size = size;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            byte[] buffer = new byte[Math.min(dst.remaining(), 64 * 1024)];
            int n = in.read(buffer);
            if (n > 0) {
                dst.put(buffer, 0, n);
                position += n;
            }
            return n;
        }

        @Override
        public int write(ByteBuffer src) {
            throw new NonWritableChannelException();
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            if (newPosition < position) {
                throw new UnsupportedOperationException("Cofanie w pliku na serwerze nie jest obsługiwane.");
            }
            position += in.skip(newPosition - position);
            return this;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public SeekableByteChannel truncate(long size) {
            throw new NonWritableChannelException();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() throws IOException {
            open = false;
            in.close();
        }
    }

    private static final class WriteChannel implements SeekableByteChannel {
        private final OutputStream out;
        private long position;
        private boolean open = true;

        WriteChannel(OutputStream out) {
            this.out = out;
        }

        @Override
        public int read(ByteBuffer dst) {
            throw new NonReadableChannelException();
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            int n = src.remaining();
            byte[] buffer = new byte[n];
            src.get(buffer);
            out.write(buffer);
            position += n;
            return n;
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        public SeekableByteChannel position(long newPosition) {
            if (newPosition != position) {
                throw new UnsupportedOperationException();
            }
            return this;
        }

        @Override
        public long size() {
            return position;
        }

        @Override
        public SeekableByteChannel truncate(long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() throws IOException {
            open = false;
            out.close();
        }
    }
}
