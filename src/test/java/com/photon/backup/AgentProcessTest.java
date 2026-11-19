package com.photon.backup;

import com.photon.phonearchive.PhoneArchiveOptions;
import com.photon.phonearchive.PhoneArchiveProfileStore;
import com.photon.profiles.Profile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.util.WaitForAsyncUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Agent uruchomiony tak, jak robi to PhotoN: osobny proces Javy, ścieżka klas bez JavaFX.
 * Sprawdza start, stan, zatrzymanie poleceniem i zwolnienie blokady.
 */
class AgentProcessTest {

    @TempDir
    Path dir;

    @Test
    void runsAsSeparateProcessWithoutJavaFxAndStopsOnCommand() throws Exception {
        Path data = dir.resolve("dane");
        Path upload = Files.createDirectories(dir.resolve("Upload"));
        new PhoneArchiveProfileStore(data.resolve("profiles").resolve("phone-archive"))
                .save(new Profile<>("Telefon", Instant.now(),
                        new PhoneArchiveOptions(upload, dir.resolve("Archiwum"), true)));

        List<String> command = AgentControl.command(List.of("Telefon"), data.toString());
        String classPath = command.get(command.indexOf("-cp") + 1);
        for (String entry : classPath.split(File.pathSeparator)) {
            assertFalse(entry.replace('\\', '/').contains("/org/openjfx/"), "JavaFX na ścieżce klas agenta: " + entry);
        }
        assertTrue(command.contains("-Xmx512m"));

        Path out = dir.resolve("agent.out");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out.toFile()).start();
        AgentControl control = new AgentControl(
                new AgentFiles(data.resolve("backup"), data.resolve("logs").resolve("kopia-zapasowa.log"), out), null);
        try {
            WaitForAsyncUtils.waitFor(30, TimeUnit.SECONDS,
                    () -> control.isRunning() && control.readStatus().map(s -> s.profiles().get(0).schedule().lastCheck() != null && s.profiles().get(0).schedule().eventDriven()).orElse(false));
            AgentStatus status = control.readStatus().orElseThrow();
            assertEquals(AgentStatus.State.RUNNING, status.state());
            assertEquals(process.pid(), status.pid());
            assertEquals("Telefon", status.profiles().get(0).profile());
            assertEquals(3600, status.profiles().get(0).schedule().intervalSeconds(), "domyślnie kontrolne sprawdzanie co godzinę");

            control.send(AgentCommand.of(AgentCommand.Type.STOP));
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "agent zakończył się po poleceniu");
            assertEquals(0, process.exitValue(), Files.readString(out));
            assertFalse(control.isRunning());
            assertEquals(AgentStatus.State.STOPPED, control.readStatus().orElseThrow().state());
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void consoleCommandsWorkWithoutRunningAgent() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        assertEquals(BackupAgent.EXIT_ERROR, BackupAgent.run(new String[]{"--profile", "Nie ma takiego"}, out));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("Nie ma profilu"));
        assertEquals(BackupAgent.EXIT_OK, BackupAgent.run(new String[]{"--help"}, out));
        assertEquals(BackupAgent.EXIT_ERROR, BackupAgent.run(new String[]{"--nieznane"}, out));
    }
}
