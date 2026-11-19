package com.photon.remote;

import com.fasterxml.jackson.core.type.TypeReference;
import com.photon.AppPaths;
import com.photon.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Jedno miejsce konfiguracji serwerów zdalnych dla całej aplikacji (i agenta kopii zapasowej):
 * plik {@code servers.json} w folderze danych PhotoN.
 * <p>
 * Hasła są zapisywane zwykłym tekstem — każdy, kto ma dostęp do folderu danych, może je odczytać.
 */
public final class RemoteServers {

    private static final TypeReference<List<RemoteServer>> TYPE = new TypeReference<>() {
    };

    private static Path file;
    private static List<RemoteServer> servers;
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private RemoteServers() {
    }

    /** Plik konfiguracji (domyślnie {@code servers.json} w folderze danych). */
    public static synchronized Path file() {
        if (file == null) {
            file = AppPaths.dataDir().resolve("servers.json");
        }
        return file;
    }

    /** Używa innego pliku konfiguracji (testy). */
    public static synchronized void useFile(Path path) {
        file = path;
        servers = null;
        RemoteFileSystemProvider.instance().closeAll();
    }

    public static synchronized List<RemoteServer> all() {
        if (servers == null) {
            servers = load(file());
        }
        return List.copyOf(servers);
    }

    public static Optional<RemoteServer> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return all().stream().filter(s -> s.name().equalsIgnoreCase(name)).findFirst();
    }

    public static List<String> names() {
        return all().stream().map(RemoteServer::name).toList();
    }

    /** Sprawdza listę (nazwy, poprawność); zwraca opis błędu albo null. */
    public static String validate(List<RemoteServer> list) {
        Set<String> seen = new HashSet<>();
        for (RemoteServer server : list) {
            String problem = server.validate();
            if (problem != null) {
                return (server.name().isEmpty() ? "" : "„" + server.name() + "”: ") + problem;
            }
            if (!seen.add(server.name().toLowerCase(Locale.ROOT))) {
                return "Nazwa połączenia „" + server.name() + "” występuje więcej niż raz.";
            }
        }
        return null;
    }

    /** Zapisuje całą listę. Połączenia, których konfiguracja się zmieniła, są zamykane. */
    public static void save(List<RemoteServer> list) throws IOException {
        String problem = validate(list);
        if (problem != null) {
            throw new IOException(problem);
        }
        List<RemoteServer> previous;
        synchronized (RemoteServers.class) {
            previous = servers == null ? load(file()) : servers;
            Json.writeAtomically(file(), list);
            servers = new ArrayList<>(list);
        }
        for (RemoteServer old : previous) {
            Optional<RemoteServer> now = list.stream().filter(s -> s.name().equalsIgnoreCase(old.name())).findFirst();
            if (now.isEmpty() || !now.get().sameConnection(old)) {
                RemoteFileSystemProvider.instance().close(old.name());
            }
        }
        listeners.forEach(Runnable::run);
    }

    /** Zapamiętuje odcisk klucza serwera SFTP (przy pierwszym połączeniu). */
    static void rememberHostKey(String name, String hostKey) {
        try {
            List<RemoteServer> list = new ArrayList<>(load(file())); // świeży odczyt — plik mógł zmienić PhotoN lub agent
            boolean changed = false;
            for (int i = 0; i < list.size(); i++) {
                RemoteServer server = list.get(i);
                if (server.name().equalsIgnoreCase(name) && server.hostKey() == null) {
                    list.set(i, server.withHostKey(hostKey));
                    changed = true;
                }
            }
            if (changed) {
                synchronized (RemoteServers.class) {
                    Json.writeAtomically(file(), list);
                    servers = list;
                }
                listeners.forEach(Runnable::run);
            }
        } catch (IOException e) {
            System.err.println("Nie udało się zapamiętać klucza serwera " + name + ": " + e.getMessage());
        }
    }

    /** Wczytuje plik ponownie (np. zmieniony przez drugi proces). */
    public static synchronized void reload() {
        servers = null;
    }

    public static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    private static List<RemoteServer> load(Path path) {
        if (!Files.isRegularFile(path)) {
            return new ArrayList<>();
        }
        try {
            List<RemoteServer> list = Json.mapper().readValue(path.toFile(), TYPE);
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (IOException e) {
            System.err.println("Nie udało się wczytać " + path + ": " + e.getMessage());
            return new ArrayList<>();
        }
    }
}
