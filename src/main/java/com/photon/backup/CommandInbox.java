package com.photon.backup;

import com.photon.Json;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Polecenia dla agenta zapisane przez PhotoN lub konsolę w {@code backup/commands/*.json}. Każde polecenie
 * jest odczytywane raz (plik jest usuwany); polecenia sprzed startu agenta są pomijane.
 */
final class CommandInbox {

    /** Plik, którego nie da się odczytać tak długo, jest uznawany za uszkodzony i usuwany. */
    private static final Duration BROKEN_AFTER = Duration.ofSeconds(60);

    private final Path folder;

    CommandInbox(Path folder) {
        this.folder = folder;
    }

    /** Nowe polecenia (najstarsze pierwsze), wydane po {@code since}. */
    List<AgentCommand> take(Instant since) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.json")) {
            stream.forEach(files::add);
        } catch (IOException e) {
            return List.of();
        }
        files.sort(null);
        List<AgentCommand> commands = new ArrayList<>();
        for (Path file : files) {
            AgentCommand command;
            try (var in = Files.newInputStream(file)) {
                command = Json.mapper().readValue(in, AgentCommand.class);
            } catch (IOException e) {
                // plik mógł być jeszcze zapisywany — spróbujemy przy następnej okazji, chyba że jest stary
                if (isOld(file)) {
                    deleteQuietly(file);
                }
                continue;
            }
            deleteQuietly(file);
            if (command.type() != null && (command.createdAt() == null || !command.createdAt().isBefore(since))) {
                commands.add(command);
            }
        }
        return commands;
    }

    private static boolean isOld(Path file) {
        try {
            return Duration.between(Files.getLastModifiedTime(file).toInstant(), Instant.now())
                    .compareTo(BROKEN_AFTER) > 0;
        } catch (IOException e) {
            return true;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // ignorujemy
        }
    }
}
