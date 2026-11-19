package com.photon.remote;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Otwarte połączenia z jednym serwerem, używane ponownie między operacjami (logowanie trwa).
 * Każda operacja dostaje połączenie na wyłączność; gdy wszystkie są zajęte (np. kopiowanie między
 * dwoma folderami tego samego serwera), otwierane jest kolejne.
 */
final class ClientPool {

    /** Operacja na połączeniu. */
    interface Operation<T> {
        T run(RemoteClient client) throws IOException;
    }

    private static final int MAX_IDLE = 3;
    /** Połączenie bezczynne dłużej jest sprawdzane przed użyciem (serwery zamykają bezczynne połączenia). */
    private static final Duration CHECK_AFTER = Duration.ofSeconds(20);

    private record Idle(RemoteClient client, long since) {
    }

    /**
     * Po nieudanej próbie połączenia kolejne przez chwilę od razu zgłaszają ten sam błąd — inaczej niedostępny
     * serwer blokowałby każdą operację na czas oczekiwania na połączenie (np. przy każdej zmianie w formularzu).
     */
    static final Duration RETRY_AFTER_FAILURE = Duration.ofSeconds(30);

    private final String name;
    private final Deque<Idle> idle = new ArrayDeque<>();
    private boolean closed;
    private volatile IOException lastFailure;
    private volatile long lastFailureAt;

    ClientPool(String name) {
        this.name = name;
    }

    /**
     * Wykonuje operację. Gdy ponownie użyte połączenie okaże się zerwane, operacja jest powtarzana raz
     * na nowym połączeniu.
     */
    <T> T call(Operation<T> operation) throws IOException {
        Borrowed borrowed = borrow();
        try {
            T result = operation.run(borrowed.client);
            release(borrowed.client, true);
            return result;
        } catch (IOException | RuntimeException e) {
            boolean logical = isLogical(e);
            release(borrowed.client, logical);
            if (logical || !borrowed.reused) {
                throw e;
            }
        }
        RemoteClient fresh = connect();
        try {
            T result = operation.run(fresh);
            release(fresh, true);
            return result;
        } catch (IOException | RuntimeException e) {
            release(fresh, isLogical(e));
            throw e;
        }
    }

    /** Pożycza połączenie na dłużej (np. na czas odczytu strumienia); trzeba je oddać przez {@link #release}. */
    RemoteClient borrowClient() throws IOException {
        return borrow().client;
    }

    void release(RemoteClient client, boolean healthy) {
        synchronized (this) {
            if (healthy && !closed && idle.size() < MAX_IDLE) {
                idle.push(new Idle(client, System.nanoTime()));
                return;
            }
        }
        closeQuietly(client);
    }

    synchronized void close() {
        closed = true;
        idle.forEach(i -> closeQuietly(i.client));
        idle.clear();
    }

    /** Brak pliku, plik istnieje, brak uprawnień — połączenie jest w porządku. */
    static boolean isLogical(Exception e) {
        return e instanceof NoSuchFileException || e instanceof FileAlreadyExistsException
                || e instanceof AccessDeniedException
                || e instanceof FileSystemException && !(e.getCause() instanceof IOException);
    }

    private record Borrowed(RemoteClient client, boolean reused) {
    }

    private Borrowed borrow() throws IOException {
        RemoteServer server = currentServer();
        while (true) {
            Idle candidate;
            synchronized (this) {
                candidate = idle.poll();
            }
            if (candidate == null) {
                return new Borrowed(connect(server), false);
            }
            boolean sameConfig = candidate.client.server().sameConnection(server);
            boolean stale = System.nanoTime() - candidate.since > CHECK_AFTER.toNanos();
            if (sameConfig && (!stale || candidate.client.isAlive())) {
                return new Borrowed(candidate.client, true);
            }
            closeQuietly(candidate.client);
        }
    }

    private RemoteClient connect() throws IOException {
        return connect(currentServer());
    }

    private RemoteClient connect(RemoteServer server) throws IOException {
        IOException failure = lastFailure;
        if (failure != null && System.nanoTime() - lastFailureAt < RETRY_AFTER_FAILURE.toNanos()) {
            IOException again = failure instanceof AccessDeniedException
                    ? new AccessDeniedException(server.address(), null, failure.getMessage())
                    : new IOException(failure.getMessage());
            again.addSuppressed(failure);
            throw again;
        }
        try {
            RemoteClient client = RemoteClient.connect(server);
            lastFailure = null;
            return client;
        } catch (IOException e) {
            lastFailure = e;
            lastFailureAt = System.nanoTime();
            throw e;
        }
    }

    private RemoteServer currentServer() throws IOException {
        return RemoteServers.find(name).orElseThrow(() -> new IOException(
                "Nie ma połączenia o nazwie „" + name + "” (Ustawienia → Serwery zdalne)."));
    }

    private static void closeQuietly(RemoteClient client) {
        try {
            client.close();
        } catch (IOException | RuntimeException ignored) {
            // połączenie i tak jest porzucane
        }
    }
}
