package com.photon.remote;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;

/**
 * Jedno połączenie z serwerem (FTP/FTPS/SFTP). Nie jest bezpieczne wątkowo — używane przez jeden wątek
 * naraz (pilnuje tego {@link ClientPool}). Ścieżki są bezwzględne, z ukośnikami ({@code /folder/plik.jpg}).
 * <p>
 * Błędy „logiczne” (brak pliku, plik istnieje, brak uprawnień) są zgłaszane odpowiednimi wyjątkami
 * z {@code java.nio.file}; inne {@link IOException} oznaczają problem z połączeniem.
 */
public interface RemoteClient extends Closeable {

    /** Pozycja katalogu. */
    record Entry(String name, boolean directory, long size, Instant modified) {
    }

    RemoteServer server();

    /** Zawartość katalogu (bez {@code .} i {@code ..}). */
    List<Entry> list(String dir) throws IOException;

    /** Strumień do odczytu; zamknięcie kończy transfer. */
    InputStream read(String path) throws IOException;

    /** Strumień do zapisu (nadpisuje plik); zamknięcie kończy transfer i zgłasza ewentualny błąd. */
    OutputStream write(String path) throws IOException;

    void mkdir(String path) throws IOException;

    void deleteFile(String path) throws IOException;

    void deleteDirectory(String path) throws IOException;

    void rename(String from, String to) throws IOException;

    /** Ustawia datę modyfikacji, jeśli serwer na to pozwala. @return czy się udało */
    boolean setModified(String path, Instant time);

    /** Czy połączenie nadal działa (lekkie polecenie do serwera). */
    boolean isAlive();

    /** Otwiera połączenie i loguje się. */
    static RemoteClient connect(RemoteServer server) throws IOException {
        return server.protocol() == RemoteProtocol.SFTP ? SftpRemoteClient.connect(server) : FtpRemoteClient.connect(server);
    }

    /** Klucz serwera SFTP widziany przy połączeniu (null dla FTP). */
    default String hostKey() {
        return null;
    }

    /**
     * Wynik testu połączenia.
     *
     * @param hostKey odcisk klucza serwera SFTP (do zapamiętania) albo null
     */
    record TestResult(String message, String hostKey) {
    }

    /** Łączy się i sprawdza, czy można odczytać folder (do przycisku „Testuj połączenie”). */
    static TestResult test(RemoteServer server, String folder) throws IOException {
        long start = System.nanoTime();
        try (RemoteClient client = connect(server)) {
            String dir = folder == null || folder.isBlank() ? "/" : folder;
            int count = client.list(dir).size();
            long ms = (System.nanoTime() - start) / 1_000_000;
            String text = "Połączono z " + server.address() + " w " + ms + " ms. Folder " + dir + ": "
                    + com.photon.ui.Texts.count(count, "pozycja", "pozycje", "pozycji") + ".";
            if (client.hostKey() != null) {
                text += "\nKlucz serwera: " + client.hostKey()
                        + (server.hostKey() == null ? " (zostanie zapamiętany)." : " (zgodny z zapamiętanym).");
            }
            return new TestResult(text, client.hostKey());
        }
    }
}
