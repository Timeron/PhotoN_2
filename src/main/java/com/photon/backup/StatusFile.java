package com.photon.backup;

import com.photon.Json;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Zapis stanu agenta ({@code backup/status.json}). Stan jest zapisywany tylko przy zmianach, więc nieudany
 * zapis (np. PhotoN akurat czyta plik) nie może przepaść — jest zapamiętywany i ponawiany ({@link #retry()}).
 */
final class StatusFile {

    /** Co ile ponawiać nieudany zapis. */
    static final Duration RETRY = Duration.ofSeconds(1);

    private final Path file;
    private AgentStatus unsaved;

    StatusFile(Path file) {
        this.file = file;
    }

    /** Zapisuje stan (kilka szybkich prób); przy niepowodzeniu zapamiętuje go do {@link #retry()}. */
    void write(AgentStatus status) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                Json.writeAtomically(file, status);
                unsaved = null;
                return;
            } catch (IOException e) {
                try {
                    Thread.sleep(50L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        unsaved = status;
    }

    /** Czy czeka niezapisany stan. */
    boolean pending() {
        return unsaved != null;
    }

    /** Ponawia zapis zaległego stanu. */
    void retry() {
        AgentStatus status = unsaved;
        if (status != null) {
            write(status);
        }
    }
}
