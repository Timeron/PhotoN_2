package com.photon.backup;

import com.photon.AppPaths;
import com.photon.Json;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sterowanie agentem kopii zapasowej z zewnątrz (PhotoN, konsola): sprawdzenie, czy działa,
 * odczyt stanu i dziennika, wysyłanie poleceń i uruchamianie procesu agenta.
 */
public class AgentControl {

    /** Uruchamia agenta dla profilu (osobny proces albo — w testach — wątek). */
    public interface Launcher {
        void launch(String profileName) throws IOException;
    }

    /** Opcje JVM agenta: mała sterta, oddawanie nieużywanej pamięci systemowi, lżejsza kompilacja JIT. */
    static final List<String> JVM_OPTIONS = List.of(
            "-Xms16m", "-Xmx512m", "-XX:+UseSerialGC", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=20",
            "-XX:TieredStopAtLevel=1", "-Xss512k", "-Dfile.encoding=UTF-8");

    private final AgentFiles files;
    private final Launcher launcher;

    /** @param launcher sposób uruchamiania agenta (null = tylko podgląd i polecenia) */
    public AgentControl(AgentFiles files, Launcher launcher) {
        this.files = files;
        this.launcher = launcher;
    }

    /** Sterowanie domyślnym agentem; uruchamia go jako osobny proces Javy bez okna konsoli. */
    public static AgentControl createDefault() {
        AgentFiles files = AgentFiles.defaults();
        return new AgentControl(files, profile -> startProcess(files, profile));
    }

    public AgentFiles files() {
        return files;
    }

    /** Czy agent działa — sprawdzane blokadą pliku (zwalnianą przez system, gdy proces zginie). */
    public boolean isRunning() {
        if (!Files.exists(files.lock())) {
            return false;
        }
        try (FileChannel channel = FileChannel.open(files.lock(), StandardOpenOption.WRITE)) {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                return true;
            }
            lock.release();
            return false;
        } catch (OverlappingFileLockException e) {
            return true; // agent działa w tym samym procesie (testy)
        } catch (IOException e) {
            return true; // plik zajęty przez inny proces
        }
    }

    public Optional<AgentStatus> readStatus() {
        if (!Files.isRegularFile(files.status())) {
            return Optional.empty();
        }
        // Odczyt przez NIO: w Windows nie blokuje agentowi podmiany pliku (java.io.File by blokował),
        // więc zapis stanu się nie gubi, gdy PhotoN akurat czyta.
        try (var in = Files.newInputStream(files.status())) {
            return Optional.of(Json.mapper().readValue(in, AgentStatus.class));
        } catch (IOException e) {
            return Optional.empty(); // akurat zapisywany — spróbujemy za chwilę
        }
    }

    public List<String> logTail(int maxLines) {
        return BackupLog.tail(files.logFile(), maxLines);
    }

    /** Zapisuje polecenie dla agenta (atomowo — agent nie przeczyta połowy pliku). */
    public void send(AgentCommand command) throws IOException {
        Files.createDirectories(files.commands());
        String name = System.currentTimeMillis() + "-" + command.type().name().toLowerCase() + "-"
                + UUID.randomUUID().toString().substring(0, 8) + ".json";
        Json.writeAtomically(files.commands().resolve(name), command);
    }

    public void start(String profileName) throws IOException {
        if (launcher == null) {
            throw new IOException("Uruchamianie agenta nie jest dostępne.");
        }
        if (isRunning()) {
            throw new IOException("Agent już działa.");
        }
        launcher.launch(profileName);
    }

    // =====================================================================
    // Proces agenta
    // =====================================================================

    /** Polecenie uruchomienia agenta tą samą Javą i z tą samą ścieżką klas co PhotoN. */
    static List<String> command(String profileName) {
        return command(profileName, System.getProperty(AppPaths.DATA_DIR_PROPERTY));
    }

    /** @param dataDir folder danych PhotoN przekazywany agentowi (null = domyślny) */
    static List<String> command(String profileName, String dataDir) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.addAll(JVM_OPTIONS);
        if (dataDir != null && !dataDir.isBlank()) {
            command.add("-D" + AppPaths.DATA_DIR_PROPERTY + "=" + dataDir);
        }
        // Ścieżka do PhotoN.exe (wersja wyeksportowana) — dla „Otwórz PhotoN” w ikonie agenta.
        String app = System.getProperty("jpackage.app-path");
        if (app != null && !app.isBlank()) {
            command.add("-D" + AgentTray.APP_LAUNCHER_PROPERTY + "=" + app);
        }
        command.add("-cp");
        command.add(agentClassPath(System.getProperty("java.class.path")));
        command.add(BackupAgent.class.getName());
        command.add("--profile");
        command.add(profileName);
        return command;
    }

    /** Ścieżka klas bez bibliotek interfejsu (JavaFX, ikony, testy) — agent ich nie potrzebuje. */
    static String agentClassPath(String classPath) {
        List<String> kept = new ArrayList<>();
        for (String entry : classPath.split(File.pathSeparator)) {
            String lower = entry.replace('\\', '/').toLowerCase();
            boolean ui = lower.contains("/org/openjfx/") || lower.contains("/org/kordamp/ikonli/")
                    || lower.contains("/org/testfx/") || lower.contains("javafx-");
            if (!entry.isBlank() && !ui) {
                kept.add(entry);
            }
        }
        return String.join(File.pathSeparator, kept);
    }

    /**
     * {@code javaw.exe} bieżącej Javy (bez okna konsoli), a gdy go nie ma — {@code java}.
     * Liczone od {@code java.home}, bo w wersji wyeksportowanej proces to {@code PhotoN.exe},
     * a Java leży w jej folderze {@code runtime}.
     */
    static String javaExecutable() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        for (String name : List.of("javaw.exe", "javaw", "java.exe", "java")) {
            if (Files.isRegularFile(bin.resolve(name))) {
                return bin.resolve(name).toString();
            }
        }
        return ProcessHandle.current().info().command().orElse("java");
    }

    private static void startProcess(AgentFiles files, String profileName) throws IOException {
        Files.createDirectories(files.consoleFile().toAbsolutePath().getParent());
        ProcessBuilder builder = new ProcessBuilder(command(profileName))
                .redirectErrorStream(true)
                .redirectOutput(files.consoleFile().toFile())
                .redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
        builder.start(); // proces działa dalej po zamknięciu PhotoN
    }

    private static File nullDevice() {
        return new File(System.getProperty("os.name").toLowerCase().startsWith("windows") ? "NUL" : "/dev/null");
    }
}
