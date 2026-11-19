package com.photon.remote;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import com.photon.ui.Texts;

/** Połączenie FTP / FTPS (Apache Commons Net). */
final class FtpRemoteClient implements RemoteClient {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(60);
    private static final DateTimeFormatter MFMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    private final RemoteServer server;
    private final FTPClient ftp;
    private final boolean mlsd;

    private FtpRemoteClient(RemoteServer server, FTPClient ftp, boolean mlsd) {
        this.server = server;
        this.ftp = ftp;
        this.mlsd = mlsd;
    }

    static FtpRemoteClient connect(RemoteServer server) throws IOException {
        FTPClient ftp = switch (server.protocol()) {
            case FTPS -> new FTPSClient(false);
            case FTPS_IMPLICIT -> new FTPSClient(true);
            default -> new FTPClient();
        };
        ftp.setControlEncoding("UTF-8");
        ftp.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        ftp.setDefaultTimeout((int) READ_TIMEOUT.toMillis());
        ftp.setDataTimeout(READ_TIMEOUT);
        try {
            ftp.connect(server.host(), server.effectivePort());
            if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
                throw new IOException("serwer odrzucił połączenie: " + reply(ftp));
            }
            ftp.setSoTimeout((int) READ_TIMEOUT.toMillis());
            String user = server.user().isEmpty() ? "anonymous" : server.user();
            String password = server.user().isEmpty() && server.password().isEmpty() ? "photon@" : server.password();
            if (!ftp.login(user, password)) {
                throw new AccessDeniedException(server.address(), null, "logowanie nieudane — sprawdź użytkownika i hasło ("
                        + reply(ftp) + ")");
            }
            if (ftp instanceof FTPSClient ftps) {
                ftps.execPBSZ(0);
                ftps.execPROT("P"); // szyfrowane także przesyłanie plików
            }
            if (!ftp.setFileType(FTP.BINARY_FILE_TYPE)) {
                throw new IOException("serwer nie przełączył się w tryb binarny: " + reply(ftp));
            }
            if (server.passive()) {
                ftp.enterLocalPassiveMode();
            }
            boolean mlsd = false;
            if (ftp.features()) {
                String features = ftp.getReplyString().toUpperCase();
                mlsd = features.contains("MLSD") || features.contains("MLST");
                if (features.contains("UTF8")) {
                    ftp.sendCommand("OPTS UTF8 ON");
                }
            }
            return new FtpRemoteClient(server, ftp, mlsd);
        } catch (IOException e) {
            disconnect(ftp);
            throw e instanceof AccessDeniedException ? e
                    : new IOException("Nie można połączyć z " + server.address() + ": " + Texts.errorMessage(e), e);
        }
    }

    @Override
    public RemoteServer server() {
        return server;
    }

    @Override
    public List<Entry> list(String dir) throws IOException {
        FTPFile[] files = mlsd ? ftp.mlistDir(dir) : ftp.listFiles(dir);
        int code = ftp.getReplyCode();
        if (!FTPReply.isPositiveCompletion(code)) {
            throw logicalError(dir, code);
        }
        List<Entry> entries = new ArrayList<>();
        for (FTPFile file : files) {
            if (file == null) {
                continue; // wiersz, którego nie udało się zrozumieć
            }
            String name = file.getName();
            int slash = name.lastIndexOf('/');
            if (slash >= 0) {
                name = name.substring(slash + 1); // niektóre serwery zwracają pełne ścieżki
            }
            if (name.isEmpty() || name.equals(".") || name.equals("..")) {
                continue;
            }
            Instant modified = file.getTimestamp() == null ? Instant.EPOCH : file.getTimestamp().toInstant();
            entries.add(new Entry(name, file.isDirectory(), Math.max(0, file.getSize()), modified));
        }
        return entries;
    }

    @Override
    public InputStream read(String path) throws IOException {
        InputStream in = ftp.retrieveFileStream(path);
        if (in == null) {
            throw logicalError(path, ftp.getReplyCode());
        }
        return new FilterInputStream(in) {
            private boolean closed;

            @Override
            public void close() throws IOException {
                if (closed) {
                    return;
                }
                closed = true;
                super.close();
                if (!ftp.completePendingCommand()) {
                    throw new IOException("odczyt " + path + " nieudany: " + reply(ftp));
                }
            }
        };
    }

    @Override
    public OutputStream write(String path) throws IOException {
        OutputStream out = ftp.storeFileStream(path);
        if (out == null) {
            throw logicalError(path, ftp.getReplyCode());
        }
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
                super.close();
                if (!ftp.completePendingCommand()) {
                    throw new IOException("zapis " + path + " nieudany: " + reply(ftp));
                }
            }
        };
    }

    @Override
    public void mkdir(String path) throws IOException {
        if (!ftp.makeDirectory(path)) {
            throw logicalError(path, ftp.getReplyCode());
        }
    }

    @Override
    public void deleteFile(String path) throws IOException {
        if (!ftp.deleteFile(path)) {
            throw logicalError(path, ftp.getReplyCode());
        }
    }

    @Override
    public void deleteDirectory(String path) throws IOException {
        if (!ftp.removeDirectory(path)) {
            throw logicalError(path, ftp.getReplyCode());
        }
    }

    @Override
    public void rename(String from, String to) throws IOException {
        if (!ftp.rename(from, to)) {
            throw logicalError(from, ftp.getReplyCode());
        }
    }

    @Override
    public boolean setModified(String path, Instant time) {
        try {
            return ftp.setModificationTime(path, MFMT.format(time));
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean isAlive() {
        try {
            return ftp.isConnected() && ftp.sendNoOp();
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public void close() {
        try {
            if (ftp.isConnected()) {
                ftp.logout();
            }
        } catch (IOException ignored) {
            // i tak rozłączamy
        }
        disconnect(ftp);
    }

    /**
     * Odpowiedź 5xx na polecenie dotyczące pliku = błąd „logiczny” (brak pliku, brak uprawnień),
     * inne kody (4xx, zerwane połączenie) = problem z połączeniem.
     */
    private IOException logicalError(String path, int code) {
        String text = reply(ftp);
        if (code == FTPReply.FILE_UNAVAILABLE || code == FTPReply.ACTION_ABORTED && text.toLowerCase().contains("not found")) {
            String lower = text.toLowerCase();
            if (lower.contains("permission") || lower.contains("denied") || lower.contains("access")) {
                return new AccessDeniedException(path, null, text);
            }
            return new NoSuchFileException(path, null, text);
        }
        if (code >= 500) {
            return new AccessDeniedException(path, null, text);
        }
        return new IOException(path + ": " + (text.isEmpty() ? "brak odpowiedzi serwera" : text));
    }

    private static String reply(FTPClient ftp) {
        String text = ftp.getReplyString();
        return text == null ? "" : text.trim();
    }

    private static void disconnect(FTPClient ftp) {
        try {
            if (ftp.isConnected()) {
                ftp.disconnect();
            }
        } catch (IOException ignored) {
            // nic więcej nie zrobimy
        }
    }

}
