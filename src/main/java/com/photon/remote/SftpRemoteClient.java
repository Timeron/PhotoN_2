package com.photon.remote;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Vector;

/**
 * Połączenie SFTP (JSch). Klucz serwera jest zapamiętywany przy pierwszym udanym połączeniu
 * ({@link RemoteServer#hostKey()}); jeśli później się zmieni, połączenie jest odrzucane, zanim
 * hasło trafi do serwera.
 */
final class SftpRemoteClient implements RemoteClient {

    private final RemoteServer server;
    private final Session session;
    private final ChannelSftp sftp;
    private final String hostKey;

    private SftpRemoteClient(RemoteServer server, Session session, ChannelSftp sftp, String hostKey) {
        this.server = server;
        this.session = session;
        this.sftp = sftp;
        this.hostKey = hostKey;
    }

    @Override
    public String hostKey() {
        return hostKey;
    }

    static SftpRemoteClient connect(RemoteServer server) throws IOException {
        KeyCheck keys = new KeyCheck(server.hostKey());
        Session session = null;
        try {
            JSch jsch = new JSch();
            session = jsch.getSession(server.user(), server.host(), server.effectivePort());
            session.setPassword(server.password());
            session.setUserInfo(new PasswordInfo(server.password()));
            session.setHostKeyRepository(keys);
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
            session.setTimeout((int) FtpRemoteClient.READ_TIMEOUT.toMillis());
            session.connect((int) FtpRemoteClient.CONNECT_TIMEOUT.toMillis());
            ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect((int) FtpRemoteClient.CONNECT_TIMEOUT.toMillis());
            if (server.hostKey() == null && keys.seen != null) {
                RemoteServers.rememberHostKey(server.name(), keys.seen);
            }
            return new SftpRemoteClient(server, session, channel, keys.seen);
        } catch (JSchException e) {
            if (session != null) {
                session.disconnect();
            }
            if (keys.changed) {
                throw new IOException("Klucz serwera " + server.address() + " jest inny niż zapamiętany ("
                        + server.hostKey() + ", teraz " + keys.seen + "). To może oznaczać podszywanie się pod serwer. "
                        + "Jeśli serwer był reinstalowany, wyczyść zapamiętany klucz w Ustawieniach → Serwery.", e);
            }
            String text = FtpRemoteClient.message(e);
            if (text.toLowerCase().contains("auth")) {
                throw new AccessDeniedException(server.address(), null,
                        "logowanie nieudane — sprawdź użytkownika i hasło (" + text + ")");
            }
            throw new IOException("Nie można połączyć z " + server.address() + ": " + text, e);
        }
    }

    @Override
    public RemoteServer server() {
        return server;
    }

    @Override
    public List<Entry> list(String dir) throws IOException {
        List<Entry> entries = new ArrayList<>();
        try {
            Vector<?> items = sftp.ls(dir);
            for (Object item : items) {
                ChannelSftp.LsEntry entry = (ChannelSftp.LsEntry) item;
                String name = entry.getFilename();
                if (name.equals(".") || name.equals("..")) {
                    continue;
                }
                SftpATTRS attrs = entry.getAttrs();
                if (attrs.isLink()) {
                    try {
                        attrs = sftp.stat(join(dir, name)); // dowiązanie — liczy się to, na co wskazuje
                    } catch (SftpException e) {
                        continue; // zerwane dowiązanie
                    }
                }
                entries.add(new Entry(name, attrs.isDir(), attrs.getSize(),
                        Instant.ofEpochSecond(Integer.toUnsignedLong(attrs.getMTime()))));
            }
        } catch (SftpException e) {
            throw map(dir, e);
        }
        return entries;
    }

    @Override
    public InputStream read(String path) throws IOException {
        try {
            return new FilterInputStream(sftp.get(path)) {
            };
        } catch (SftpException e) {
            throw map(path, e);
        }
    }

    @Override
    public OutputStream write(String path) throws IOException {
        try {
            OutputStream out = sftp.put(path, ChannelSftp.OVERWRITE);
            return new FilterOutputStream(out) {
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    out.write(b, off, len);
                }
            };
        } catch (SftpException e) {
            throw map(path, e);
        }
    }

    @Override
    public void mkdir(String path) throws IOException {
        try {
            sftp.mkdir(path);
        } catch (SftpException e) {
            throw map(path, e);
        }
    }

    @Override
    public void deleteFile(String path) throws IOException {
        try {
            sftp.rm(path);
        } catch (SftpException e) {
            throw map(path, e);
        }
    }

    @Override
    public void deleteDirectory(String path) throws IOException {
        try {
            sftp.rmdir(path);
        } catch (SftpException e) {
            throw map(path, e);
        }
    }

    @Override
    public void rename(String from, String to) throws IOException {
        try {
            sftp.rename(from, to);
        } catch (SftpException e) {
            throw map(from, e);
        }
    }

    @Override
    public boolean setModified(String path, Instant time) {
        try {
            sftp.setMtime(path, (int) time.getEpochSecond());
            return true;
        } catch (SftpException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean isAlive() {
        try {
            if (!session.isConnected() || sftp.isClosed()) {
                return false;
            }
            sftp.stat("/");
            return true;
        } catch (SftpException e) {
            return e.id != ChannelSftp.SSH_FX_CONNECTION_LOST && e.id != ChannelSftp.SSH_FX_NO_CONNECTION;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void close() {
        sftp.disconnect();
        session.disconnect();
    }

    private static IOException map(String path, SftpException e) {
        return switch (e.id) {
            case ChannelSftp.SSH_FX_NO_SUCH_FILE -> new NoSuchFileException(path, null, e.getMessage());
            case ChannelSftp.SSH_FX_PERMISSION_DENIED -> new AccessDeniedException(path, null, e.getMessage());
            case ChannelSftp.SSH_FX_FAILURE -> new AccessDeniedException(path, null,
                    e.getMessage() == null ? "operacja odrzucona przez serwer" : e.getMessage());
            case 11 /* SSH_FX_FILE_ALREADY_EXISTS (v5+) */ -> new FileAlreadyExistsException(path);
            default -> new IOException(path + ": " + FtpRemoteClient.message(e), e);
        };
    }

    private static String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    /** Odcisk klucza w formacie OpenSSH: {@code SHA256:…}. */
    static String fingerprint(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Zaufanie przy pierwszym połączeniu: nieznany klucz jest przyjmowany, zmieniony — odrzucany. */
    private static final class KeyCheck implements HostKeyRepository {
        private final String expected;
        private String seen;
        private boolean changed;

        KeyCheck(String expected) {
            this.expected = expected;
        }

        @Override
        public int check(String host, byte[] key) {
            seen = fingerprint(key);
            if (expected == null || expected.equals(seen)) {
                return OK;
            }
            changed = true;
            return CHANGED;
        }

        @Override
        public void add(HostKey hostkey, UserInfo ui) {
        }

        @Override
        public void remove(String host, String type) {
        }

        @Override
        public void remove(String host, String type, byte[] key) {
        }

        @Override
        public String getKnownHostsRepositoryID() {
            return "PhotoN";
        }

        @Override
        public HostKey[] getHostKey() {
            return new HostKey[0];
        }

        @Override
        public HostKey[] getHostKey(String host, String type) {
            return new HostKey[0];
        }
    }

    /** Hasło także dla logowania „keyboard-interactive” (częste na NAS-ach). */
    private record PasswordInfo(String password) implements UserInfo, UIKeyboardInteractive {
        @Override
        public String getPassphrase() {
            return null;
        }

        @Override
        public String getPassword() {
            return password;
        }

        @Override
        public boolean promptPassword(String message) {
            return true;
        }

        @Override
        public boolean promptPassphrase(String message) {
            return false;
        }

        @Override
        public boolean promptYesNo(String message) {
            return false;
        }

        @Override
        public void showMessage(String message) {
        }

        @Override
        public String[] promptKeyboardInteractive(String destination, String name, String instruction,
                                                  String[] prompt, boolean[] echo) {
            String[] answers = new String[prompt.length];
            java.util.Arrays.fill(answers, password);
            return answers;
        }
    }
}
