package com.photon.remote;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Ścieżka na serwerze zdalnym. Tekstowo: {@code [Nazwa połączenia]/folder/plik.jpg} — w tej postaci jest
 * zapisywana w profilach i pokazywana w aplikacji. Ścieżki względne (np. wynik {@link #relativize}) nie mają
 * nazwy połączenia: {@code folder/plik.jpg}.
 */
final class RemotePath implements Path {

    private final RemoteFileSystem fs;
    private final boolean absolute;
    private final String[] names;

    RemotePath(RemoteFileSystem fs, boolean absolute, String[] names) {
        this.fs = fs;
        this.absolute = absolute;
        this.names = names;
    }

    /** Ścieżka z tekstu; separatorem może być {@code /} albo {@code \}. */
    static RemotePath parse(RemoteFileSystem fs, String text) {
        boolean absolute = text.startsWith("/") || text.startsWith("\\");
        String[] names = Arrays.stream(text.split("[/\\\\]+")).filter(s -> !s.isEmpty()).toArray(String[]::new);
        return new RemotePath(fs, absolute, names);
    }

    /** Ścieżka w postaci, w jakiej rozumie ją serwer: {@code /folder/plik.jpg}. */
    String remote() {
        String joined = String.join("/", names);
        return absolute ? "/" + joined : joined;
    }

    String[] names() {
        return names;
    }

    @Override
    public RemoteFileSystem getFileSystem() {
        return fs;
    }

    @Override
    public boolean isAbsolute() {
        return absolute;
    }

    @Override
    public Path getRoot() {
        return absolute ? new RemotePath(fs, true, new String[0]) : null;
    }

    @Override
    public Path getFileName() {
        return names.length == 0 ? null : new RemotePath(fs, false, new String[]{names[names.length - 1]});
    }

    @Override
    public Path getParent() {
        if (names.length == 0 || names.length == 1 && !absolute) {
            return null;
        }
        return new RemotePath(fs, absolute, Arrays.copyOf(names, names.length - 1));
    }

    @Override
    public int getNameCount() {
        return names.length;
    }

    @Override
    public Path getName(int index) {
        if (index < 0 || index >= names.length) {
            throw new IllegalArgumentException("index " + index);
        }
        return new RemotePath(fs, false, new String[]{names[index]});
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        if (beginIndex < 0 || endIndex > names.length || beginIndex >= endIndex) {
            throw new IllegalArgumentException(beginIndex + ".." + endIndex);
        }
        return new RemotePath(fs, false, Arrays.copyOfRange(names, beginIndex, endIndex));
    }

    @Override
    public boolean startsWith(Path other) {
        if (!(other instanceof RemotePath o) || !o.fs.equals(fs) || o.absolute != absolute
                || o.names.length > names.length) {
            return false;
        }
        for (int i = 0; i < o.names.length; i++) {
            if (!o.names[i].equals(names[i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean endsWith(Path other) {
        if (!(other instanceof RemotePath o) || !o.fs.equals(fs)) {
            return false;
        }
        if (o.absolute) {
            return equals(o);
        }
        if (o.names.length > names.length || o.names.length == 0) {
            return false;
        }
        int offset = names.length - o.names.length;
        for (int i = 0; i < o.names.length; i++) {
            if (!o.names[i].equals(names[offset + i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Path normalize() {
        List<String> result = new ArrayList<>();
        for (String name : names) {
            if (name.equals(".")) {
                continue;
            }
            if (name.equals("..")) {
                if (!result.isEmpty() && !result.get(result.size() - 1).equals("..")) {
                    result.remove(result.size() - 1);
                    continue;
                }
                if (absolute) {
                    continue; // nad korzeniem nic nie ma
                }
            }
            result.add(name);
        }
        return new RemotePath(fs, absolute, result.toArray(String[]::new));
    }

    @Override
    public Path resolve(Path other) {
        RemotePath o;
        if (other instanceof RemotePath r) {
            o = r;
        } else {
            if (other.isAbsolute()) {
                throw new ProviderMismatchException("Nie można połączyć ścieżki zdalnej z lokalną: " + other);
            }
            o = parse(fs, other.toString()); // np. ścieżka względna z dysku lokalnego
        }
        if (o.absolute) {
            return o;
        }
        String[] joined = Arrays.copyOf(names, names.length + o.names.length);
        System.arraycopy(o.names, 0, joined, names.length, o.names.length);
        return new RemotePath(fs, absolute, joined);
    }

    @Override
    public Path resolve(String other) {
        return resolve(parse(fs, other));
    }

    @Override
    public Path relativize(Path other) {
        if (!(other instanceof RemotePath o) || o.absolute != absolute) {
            throw new IllegalArgumentException("Ścieżki różnego rodzaju: " + this + ", " + other);
        }
        int common = 0;
        while (common < names.length && common < o.names.length && names[common].equals(o.names[common])) {
            common++;
        }
        List<String> result = new ArrayList<>();
        for (int i = common; i < names.length; i++) {
            result.add("..");
        }
        result.addAll(Arrays.asList(o.names).subList(common, o.names.length));
        return new RemotePath(fs, false, result.toArray(String[]::new));
    }

    @Override
    public URI toUri() {
        try {
            return new URI(RemoteFileSystemProvider.SCHEME, "//" + fs.name() + toAbsolutePath().remote(), null);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public RemotePath toAbsolutePath() {
        if (absolute) {
            return this;
        }
        return new RemotePath(fs, true, names);
    }

    @Override
    public Path toRealPath(LinkOption... options) throws IOException {
        Path real = toAbsolutePath().normalize();
        fs.stat((RemotePath) real);
        return real;
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) {
        throw new UnsupportedOperationException("Serwer zdalny nie powiadamia o zmianach — tylko sprawdzanie cykliczne.");
    }

    @Override
    public int compareTo(Path other) {
        return toString().compareTo(other.toString());
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof RemotePath o && o.fs.equals(fs) && o.absolute == absolute && Arrays.equals(o.names, names);
    }

    @Override
    public int hashCode() {
        return fs.hashCode() * 31 + Arrays.hashCode(names) + (absolute ? 1 : 0);
    }

    @Override
    public String toString() {
        return absolute ? "[" + fs.name() + "]" + remote() : remote();
    }
}
