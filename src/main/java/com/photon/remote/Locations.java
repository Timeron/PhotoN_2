package com.photon.remote;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Lokalizacje folderów: na dysku tego komputera albo na serwerze zdalnym. Folder na serwerze zapisuje się
 * jako {@code [Nazwa połączenia]/folder/na/serwerze} — nazwa wskazuje połączenie z {@link RemoteServers},
 * więc adres, użytkownik i hasło są w jednym miejscu dla całej aplikacji.
 */
public final class Locations {

    private Locations() {
    }

    /**
     * Ścieżka z tekstu wpisanego przez użytkownika lub zapisanego w profilu.
     *
     * @throws InvalidPathException gdy tekst nie jest poprawną ścieżką
     */
    public static Path parse(String text) {
        String value = text.trim();
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 0) {
                throw new InvalidPathException(text, "brak „]” po nazwie połączenia");
            }
            String server = value.substring(1, end).trim();
            if (server.isEmpty()) {
                throw new InvalidPathException(text, "brak nazwy połączenia");
            }
            return remote(server, value.substring(end + 1));
        }
        return Path.of(value);
    }

    /** Folder {@code folder} na serwerze połączenia {@code server}. */
    public static Path remote(String server, String folder) {
        String path = folder == null ? "" : folder.trim().replace('\\', '/');
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return RemoteFileSystemProvider.instance().fileSystem(server).getPath(path).normalize();
    }

    public static boolean isRemote(Path path) {
        return path != null && path.getFileSystem() instanceof RemoteFileSystem;
    }

    /** Nazwa połączenia ścieżki zdalnej albo null dla ścieżki lokalnej. */
    public static String serverName(Path path) {
        return isRemote(path) ? ((RemoteFileSystem) path.getFileSystem()).name() : null;
    }

    /** Folder na serwerze ({@code /zdjecia/2024}) albo null dla ścieżki lokalnej. */
    public static String remoteFolder(Path path) {
        return isRemote(path) ? ((RemotePath) path).toAbsolutePath().remote() : null;
    }

    /** Czy obie ścieżki leżą na tym samym „dysku” (ten sam dysk lokalny albo to samo połączenie). */
    public static boolean sameServer(Path a, Path b) {
        return isRemote(a) && isRemote(b) && a.getFileSystem() == b.getFileSystem();
    }
}
