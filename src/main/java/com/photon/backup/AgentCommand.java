package com.photon.backup;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Polecenie dla agenta, zapisywane jako plik JSON w {@code backup/commands/}. Agent wykonuje je i usuwa plik.
 * Polecenia starsze niż start agenta są pomijane (np. „zatrzymaj” wysłane, gdy agent nie działał).
 *
 * @param paths   pliki źródłowe duplikatów do usunięcia (tylko {@link Type#REMOVE_DUPLICATES})
 * @param toTrash do kosza systemowego zamiast trwale (gdy system na to pozwala)
 */
public record AgentCommand(Type type, List<Path> paths, boolean toTrash, Instant createdAt) {

    public enum Type { STOP, CHECK_NOW, REMOVE_DUPLICATES }

    public AgentCommand {
        paths = paths == null ? List.of() : List.copyOf(paths);
    }

    public static AgentCommand of(Type type) {
        return new AgentCommand(type, List.of(), false, Instant.now());
    }

    public static AgentCommand removeDuplicates(List<Path> paths, boolean toTrash) {
        return new AgentCommand(Type.REMOVE_DUPLICATES, paths, toTrash, Instant.now());
    }
}
