package com.photon.remote;

import com.photon.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pliki na prawdziwym (wbudowanym) serwerze FTP i SFTP widziane jako {@link Path}. */
class RemoteFileSystemTest {

    enum Kind { FTP, SFTP }

    @TempDir
    Path dir;

    private TestServers servers;
    private Path serverRoot;

    @BeforeEach
    void setUp() {
        servers = new TestServers();
        serverRoot = dir.resolve("serwer");
        RemoteServers.useFile(dir.resolve("servers.json"));
    }

    @AfterEach
    void tearDown() {
        servers.close();
        RemoteServers.useFile(null);
    }

    private RemoteServer start(Kind kind) throws Exception {
        RemoteServer server = kind == Kind.FTP ? servers.ftp("NAS", serverRoot)
                : servers.sftp("NAS", serverRoot, dir.resolve("hostkey.ser"));
        RemoteServers.save(List.of(server));
        return server;
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void filesOnServerBehaveLikeLocalFiles(Kind kind) throws Exception {
        start(kind);
        Path folder = Locations.parse("[NAS]/zdjęcia/2024");
        assertEquals("[NAS]/zdjęcia/2024", folder.toString());
        assertFalse(Files.exists(folder));

        Files.createDirectories(folder);
        assertTrue(Files.isDirectory(folder));
        assertTrue(Files.isDirectory(serverRoot.resolve("zdjęcia/2024")), "folder powstał na serwerze");

        Path photo = folder.resolve("IMG_1.jpg");
        Files.writeString(photo, "zdjęcie ąę");
        assertEquals("zdjęcie ąę", Files.readString(photo));
        assertEquals(Files.size(serverRoot.resolve("zdjęcia/2024/IMG_1.jpg")), Files.size(photo));
        assertTrue(Files.isRegularFile(photo));
        assertThrows(FileAlreadyExistsException.class, () -> Files.createFile(photo));

        // Przenoszenie w obrębie serwera = zmiana nazwy.
        Path renamed = Locations.parse("[NAS]/zdjęcia/IMG_1.jpg");
        Files.move(photo, renamed);
        assertFalse(Files.exists(photo));
        assertEquals("zdjęcie ąę", Files.readString(serverRoot.resolve("zdjęcia/IMG_1.jpg")));

        // Przechodzenie po drzewie.
        List<String> walked = new ArrayList<>();
        Files.walkFileTree(Locations.parse("[NAS]/zdjęcia"), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                walked.add(file + " " + attrs.size());
                return FileVisitResult.CONTINUE;
            }
        });
        assertEquals(List.of("[NAS]/zdjęcia/IMG_1.jpg " + Files.size(renamed)), walked);

        // Usuwanie.
        Files.delete(renamed);
        Files.delete(folder);
        assertFalse(Files.exists(renamed));
        assertFalse(Files.exists(serverRoot.resolve("zdjęcia/2024")));
        assertThrows(NoSuchFileException.class, () -> Files.delete(renamed));
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void copiesAndMovesBetweenServerAndDisk(Kind kind) throws Exception {
        start(kind);
        Path local = dir.resolve("lokalne/IMG_2.jpg");
        Files.createDirectories(local.getParent());
        Files.writeString(local, "z dysku");
        FileTime time = FileTime.from(Instant.parse("2020-05-06T07:08:09Z"));
        Files.setLastModifiedTime(local, time);

        Path remote = Locations.parse("[NAS]/IMG_2.jpg");
        Files.copy(local, remote, StandardCopyOption.COPY_ATTRIBUTES);
        assertEquals("z dysku", Files.readString(remote));
        assertEquals(time, Files.getLastModifiedTime(remote), "data modyfikacji przeniesiona na serwer");

        Path back = dir.resolve("z-serwera/IMG_2.jpg");
        Files.createDirectories(back.getParent());
        Files.move(remote, back);
        assertEquals("z dysku", Files.readString(back));
        assertFalse(Files.exists(remote), "przeniesienie usuwa plik z serwera");
        assertEquals(time, Files.getLastModifiedTime(back));
    }

    @Test
    void pathsAreStoredByConnectionName() throws IOException {
        record Holder(Path folder) {
        }
        Path remote = Locations.parse("[Mój NAS]\\zdjęcia\\..\\filmy");
        assertEquals("[Mój NAS]/filmy", remote.toString());
        assertEquals("Mój NAS", Locations.serverName(remote));
        assertEquals("/filmy", Locations.remoteFolder(remote));

        String json = Json.mapper().writeValueAsString(new Holder(remote));
        assertTrue(json.contains("[Mój NAS]/filmy"), json);
        assertEquals(remote, Json.mapper().readValue(json, Holder.class).folder());

        Path local = Path.of("C:\\Zdjęcia");
        assertEquals(local, Json.mapper().readValue(Json.mapper().writeValueAsString(new Holder(local)), Holder.class).folder());

        Path base = Locations.parse("[NAS]/telefon");
        Path file = Locations.parse("[NAS]/telefon/Camera/IMG.jpg");
        assertTrue(file.startsWith(base));
        assertEquals("Camera/IMG.jpg", base.relativize(file).toString());
        assertEquals(Locations.parse("[NAS]/kosz/Camera/IMG.jpg"),
                Locations.parse("[NAS]/kosz").resolve(base.relativize(file).toString()));
        assertFalse(file.startsWith(Path.of("C:\\telefon")));
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void wrongPasswordAndMissingServerAreReported(Kind kind) throws Exception {
        RemoteServer server = start(kind);
        RemoteServer wrong = new RemoteServer("Zły", server.protocol(), server.host(), server.port(), server.user(),
                "złe", true, null);
        assertThrows(AccessDeniedException.class, () -> RemoteClient.test(wrong, "/"));

        RemoteClient.TestResult ok = RemoteClient.test(server, "/");
        assertTrue(ok.message().startsWith("Połączono z"), ok.message());

        // Połączenie, którego nie ma w konfiguracji, i serwer, który nie odpowiada — folder „nie istnieje”.
        assertFalse(Files.isDirectory(Locations.parse("[Nieznany]/x")));
        RemoteServers.save(List.of(new RemoteServer("NAS", server.protocol(), "127.0.0.1", 1, server.user(),
                server.password(), true, null)));
        assertFalse(Files.isDirectory(Locations.parse("[NAS]/")));
    }

    @Test
    void sftpHostKeyIsRememberedAndChangesAreRejected() throws Exception {
        RemoteServer server = start(Kind.SFTP);
        assertNull(RemoteServers.find("NAS").orElseThrow().hostKey());

        RemoteClient.TestResult result = RemoteClient.test(server, "/");
        assertTrue(result.hostKey().startsWith("SHA256:"), result.hostKey());
        Files.writeString(Locations.parse("[NAS]/a.txt"), "a");
        assertEquals(result.hostKey(), RemoteServers.find("NAS").orElseThrow().hostKey(), "zapamiętany po połączeniu");

        RemoteServers.save(List.of(server.withHostKey("SHA256:inny")));
        IOException error = assertThrows(IOException.class, () -> RemoteClient.test(server.withHostKey("SHA256:inny"), "/"));
        assertTrue(error.getMessage().contains("inny niż zapamiętany"), error.getMessage());
        assertFalse(Files.exists(Locations.parse("[NAS]/a.txt")), "bez zgodnego klucza nic nie jest czytane");
    }

    @ParameterizedTest
    @EnumSource(Kind.class)
    void listingIsCachedBrieflyButModificationsAreSeenAtOnce(Kind kind) throws Exception {
        start(kind);
        Path folder = Locations.parse("[NAS]/f");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("a.jpg"), "1");
        Map<String, Object> attrs = Files.readAttributes(folder.resolve("a.jpg"), "size,isDirectory");
        assertEquals(1L, attrs.get("size"));
        Files.writeString(folder.resolve("a.jpg"), "12345");
        assertEquals(5, Files.size(folder.resolve("a.jpg")), "zapis przez aplikację odświeża listę");
        try (var stream = Files.list(folder)) {
            assertEquals(1, stream.count());
        }
    }
}
