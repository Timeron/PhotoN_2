package com.photon.backup;

import com.photon.AppPaths;

import java.nio.file.Path;

/**
 * Pliki, przez które agent kopii zapasowej porozumiewa się z PhotoN:
 * <ul>
 *   <li>{@code backup/agent.lock} — trzymany przez działającego agenta (gwarantuje jedną kopię i pokazuje, że żyje),</li>
 *   <li>{@code backup/status.json} — stan agenta, zapisywany po każdym sprawdzeniu i co kilkadziesiąt sekund,</li>
 *   <li>{@code backup/commands/} — polecenia od PhotoN lub konsoli (zatrzymaj, sprawdź teraz, usuń duplikaty),</li>
 *   <li>{@code logs/kopia-zapasowa.log} — dziennik zdarzeń,</li>
 *   <li>{@code logs/backup-agent.out} — wyjście procesu agenta uruchomionego z PhotoN (diagnostyka).</li>
 * </ul>
 */
public record AgentFiles(Path dir, Path logFile, Path consoleFile) {

    public static AgentFiles defaults() {
        return new AgentFiles(AppPaths.dataDir().resolve("backup"),
                AppPaths.logsDir().resolve("kopia-zapasowa.log"),
                AppPaths.logsDir().resolve("backup-agent.out"));
    }

    /** Pliki w podanym katalogu (testy). */
    public static AgentFiles in(Path dir) {
        return new AgentFiles(dir, dir.resolve("kopia-zapasowa.log"), dir.resolve("backup-agent.out"));
    }

    public Path lock() {
        return dir.resolve("agent.lock");
    }

    public Path status() {
        return dir.resolve("status.json");
    }

    public Path commands() {
        return dir.resolve("commands");
    }

    /** Ustawienia agenta ({@link AgentSettings}). */
    public Path settings() {
        return dir.resolve("agent-settings.json");
    }
}
