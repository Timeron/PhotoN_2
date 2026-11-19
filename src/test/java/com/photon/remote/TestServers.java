package com.photon.remote;

import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.ftplet.UserManager;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.testfx.util.WaitForAsyncUtils;

/** Wbudowane serwery FTP i SFTP do testów (pliki serwera leżą w folderze tymczasowym). */
public final class TestServers implements AutoCloseable {

    public static final String USER = "jan";
    public static final String PASSWORD = "tajne hasło";

    private final List<AutoCloseable> running = new ArrayList<>();

    /** Uruchamia serwer FTP z katalogiem domowym {@code root}; zwraca konfigurację połączenia. */
    public RemoteServer ftp(String name, Path root) throws Exception {
        Files.createDirectories(root);
        int port = freePort();
        FtpServerFactory factory = new FtpServerFactory();
        ListenerFactory listener = new ListenerFactory();
        listener.setPort(port);
        listener.setServerAddress("127.0.0.1");
        factory.addListener("default", listener.createListener());
        UserManager users = new PropertiesUserManagerFactory().createUserManager();
        BaseUser user = new BaseUser();
        user.setName(USER);
        user.setPassword(PASSWORD);
        user.setHomeDirectory(root.toString());
        user.setAuthorities(List.<Authority>of(new WritePermission()));
        users.save(user);
        factory.setUserManager(users);
        FtpServer server = factory.createServer();
        server.start();
        running.add(server::stop);
        return new RemoteServer(name, RemoteProtocol.FTP, "127.0.0.1", port, USER, PASSWORD, true, null);
    }

    /** Uruchamia serwer SFTP z katalogiem głównym {@code root}. */
    public RemoteServer sftp(String name, Path root, Path hostKeyFile) throws IOException {
        Files.createDirectories(root);
        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKeyFile));
        sshd.setPasswordAuthenticator((user, password, session) -> USER.equals(user) && PASSWORD.equals(password));
        sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(root));
        sshd.start();
        running.add(sshd::stop);
        return new RemoteServer(name, RemoteProtocol.SFTP, "127.0.0.1", sshd.getPort(), USER, PASSWORD, true, null);
    }

    @Override
    public void close() {
        RemoteFileSystemProvider.instance().closeAll();
        for (AutoCloseable server : running) {
            try {
                server.close();
            } catch (Exception ignored) {
                // sprzątanie po teście
            }
        }
        running.clear();
        // Zatrzymany serwer SFTP zgłasza czasem (Windows, IOCP) spóźniony wyjątek w swoim wątku. TestFX zbiera
        // wyjątki ze wszystkich wątków i rzuciłby go w następnym teście interfejsu — czekamy i czyścimy.
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        WaitForAsyncUtils.clearExceptions();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
