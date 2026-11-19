package com.photon.backup;

import com.photon.AppPaths;
import com.photon.JsonFileStore;
import com.photon.duplicates.scan.HashCache;
import com.photon.phonearchive.PhoneArchiveOptions;
import com.photon.phonearchive.PhoneArchiveProfileStore;
import com.photon.profiles.Profile;
import com.photon.settings.AppSettings;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Agent kopii zapasowej uruchamiany bez interfejsu graficznego (z PhotoN albo z konsoli).
 * <pre>
 *   photon-backup --profile "Telefon"   uruchamia archiwizację w tle według profilu (Ctrl+C kończy)
 *   photon-backup --status              pokazuje stan agenta
 *   photon-backup --stop                prosi działającego agenta o zatrzymanie
 *   photon-backup --list                wypisuje profile „Archiwizacji telefonu”
 * </pre>
 * Celowo nie używa JavaFX — proces zajmuje mało pamięci i może działać, gdy PhotoN jest zamknięty.
 */
public final class BackupAgent {

    public static final int EXIT_OK = 0;
    public static final int EXIT_ERROR = 1;
    public static final int EXIT_ALREADY_RUNNING = 2;

    private BackupAgent() {
    }

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        System.exit(run(args, out));
    }

    static int run(String[] args, PrintStream out) {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            printHelp(out);
            return args.length == 0 ? EXIT_ERROR : EXIT_OK;
        }
        AgentFiles files = AgentFiles.defaults();
        AgentControl control = new AgentControl(files, null);
        try {
            return switch (args[0]) {
                case "--profile" -> args.length < 2 ? usage(out) : runAgent(args[1], files, out);
                case "--status" -> status(control, out);
                case "--stop" -> stop(control, out);
                case "--list" -> list(out);
                default -> usage(out);
            };
        } catch (IOException e) {
            out.println("Błąd: " + e.getMessage());
            return EXIT_ERROR;
        }
    }

    private static int runAgent(String profileName, AgentFiles files, PrintStream out) throws IOException {
        Optional<Profile<PhoneArchiveOptions>> profile = PhoneArchiveProfileStore.createDefault().loadAll().stream()
                .filter(p -> p.name().equals(profileName)).findFirst();
        if (profile.isEmpty()) {
            out.println("Nie ma profilu „" + profileName + "”. Dostępne profile: photon-backup --list");
            return EXIT_ERROR;
        }
        String problem = profile.get().options().validate();
        if (problem != null) {
            out.println("Profil „" + profileName + "”: " + problem);
            return EXIT_ERROR;
        }
        AppSettings settings = new JsonFileStore<>(AppPaths.settingsFile(), AppSettings.class, AppSettings.defaults())
                .load();
        if (settings.lowPriorityScan()) {
            Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
        }
        // Agent ma własną, małą pamięć sum kontrolnych (nie wczytuje dużego pliku modułu duplikatów).
        HashCache cache = new HashCache(settings.effectiveCacheDir().resolve("hashes").resolve("backup-agent.json"))
                .load();
        // Dziennik na konsolę tylko wtedy, gdy agent działa w oknie konsoli (z PhotoN — bez konsoli).
        AgentLoop loop = AgentLoop.withDefaults(profileName, profile.get().options(), files, cache,
                System.console() != null ? out : null);

        // Ikona w zasobniku systemowym (obok zegara) — gdy system ją obsługuje.
        Optional<AgentTray> tray = AgentTray.install(loop, settings.notifyOnFinish());
        BackupLog.append(files.logFile(), java.time.Instant.now(), tray.isPresent()
                ? "Ikona w zasobniku systemowym aktywna" + (settings.notifyOnFinish() ? " (z powiadomieniami)." : ".")
                : "Zasobnik systemowy niedostępny — agent działa bez ikony.");

        CountDownLatch finished = new CountDownLatch(1);
        Thread hook = new Thread(() -> {
            loop.requestStop();
            try {
                finished.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "photon-backup-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            loop.run();
            return EXIT_OK;
        } catch (AgentLoop.AlreadyRunningException e) {
            out.println(e.getMessage() + " Zatrzymaj go: photon-backup --stop");
            return EXIT_ALREADY_RUNNING;
        } finally {
            tray.ifPresent(AgentTray::remove);
            finished.countDown();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // trwa zamykanie JVM
            }
        }
    }

    private static int status(AgentControl control, PrintStream out) {
        boolean running = control.isRunning();
        Optional<AgentStatus> status = control.readStatus();
        out.println(running ? "Agent działa." : "Agent nie działa.");
        status.ifPresent(s -> {
            out.println("Profil: " + s.profile() + "  (" + s.source() + " → " + s.target() + ")");
            if (running) {
                out.println("PID: " + s.pid() + ", od: " + s.startedAt());
                out.println("Ostatnie sprawdzenie: " + s.lastCheck() + ", następne: " + s.nextCheck()
                        + " (co " + s.intervalSeconds() + " s" + (s.notifications() ? ", z powiadomieniami" : "") + ")");
            } else if (s.stoppedAt() != null) {
                out.println("Zatrzymany: " + s.stoppedAt());
            }
            out.println("Zarchiwizowano: " + s.archived() + ", miniatury: " + s.thumbnails()
                    + ", duplikaty do śmietnika: " + s.movedDuplicates()
                    + ", duplikaty do decyzji: " + s.pendingDuplicates().size()
                    + ", czeka (przesyłanie): " + s.waiting() + ", błędy: " + s.errors());
            if (s.lastProblem() != null) {
                out.println("Problem: " + s.lastProblem());
            }
        });
        out.println("Dziennik: " + AgentFiles.defaults().logFile());
        return EXIT_OK;
    }

    private static int stop(AgentControl control, PrintStream out) throws IOException {
        if (!control.isRunning()) {
            out.println("Agent nie działa.");
            return EXIT_OK;
        }
        control.send(AgentCommand.of(AgentCommand.Type.STOP));
        for (int i = 0; i < 100 && control.isRunning(); i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        out.println(control.isRunning() ? "Wysłano polecenie zatrzymania — agent kończy bieżące sprawdzenie."
                : "Agent zatrzymany.");
        return EXIT_OK;
    }

    private static int list(PrintStream out) throws IOException {
        List<Profile<PhoneArchiveOptions>> profiles = PhoneArchiveProfileStore.createDefault().loadAll();
        if (profiles.isEmpty()) {
            out.println("Brak profili. Utwórz je w PhotoN w zakładce „Archiwizacja telefonu”.");
        }
        for (Profile<PhoneArchiveOptions> p : profiles) {
            out.println(p.name() + "    " + PhoneArchiveProfileStore.describe(p.options()));
        }
        return EXIT_OK;
    }

    private static int usage(PrintStream out) {
        printHelp(out);
        return EXIT_ERROR;
    }

    private static void printHelp(PrintStream out) {
        out.println("""
                PhotoN — kopia zapasowa w tle (archiwizacja telefonu)

                  photon-backup --profile "NAZWA"   uruchamia archiwizację według profilu (Ctrl+C kończy)
                  photon-backup --status            pokazuje stan agenta
                  photon-backup --stop              zatrzymuje działającego agenta
                  photon-backup --list              wypisuje profile „Archiwizacji telefonu”

                Folder danych: %s""".formatted(AppPaths.dataDir()));
    }

}
