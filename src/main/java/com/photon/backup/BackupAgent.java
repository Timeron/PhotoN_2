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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.photon.remote.RemoteFileSystemProvider;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Agent kopii zapasowej uruchamiany bez interfejsu graficznego (z PhotoN albo z konsoli).
 * <pre>
 *   photon-backup --profile "Telefon"   uruchamia archiwizację w tle według profilu (Ctrl+C kończy)
 *   photon-backup --profile "Telefon" --profile "Tablet"   kilka profili naraz (jeden agent)
 *   photon-backup --status              pokazuje stan agenta
 *   photon-backup --stop                prosi działającego agenta o zatrzymanie
 *   photon-backup --list                wypisuje profile „Archiwizacji telefonu”
 * </pre>
 * Celowo nie używa JavaFX — proces zajmuje mało pamięci i może działać, gdy PhotoN jest zamknięty.
 */
public final class BackupAgent {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

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
                case "--profile" -> {
                    List<String> names = profileNames(args);
                    yield names == null ? usage(out) : runAgent(names, files, out);
                }
                case "--check" -> {
                    List<String> names = profileNames(Arrays.copyOfRange(args, 1, args.length));
                    yield names == null ? usage(out) : check(names, out);
                }
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

    /** Nazwy z {@code --profile A --profile B …}; null, gdy argumenty są niepoprawne. */
    static List<String> profileNames(String[] args) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < args.length; i += 2) {
            if (!"--profile".equals(args[i]) || i + 1 >= args.length || args[i + 1].isBlank()) {
                return null;
            }
            names.add(args[i + 1]);
        }
        return names;
    }

    /** Profile o podanych nazwach albo null (komunikat o błędzie wypisany), gdy nie mogą działać razem. */
    private static List<AgentProfile> loadProfiles(List<String> profileNames, PrintStream out) throws IOException {
        List<Profile<PhoneArchiveOptions>> stored = PhoneArchiveProfileStore.createDefault().loadAll();
        List<AgentProfile> profiles = new ArrayList<>();
        for (String name : profileNames) {
            Optional<Profile<PhoneArchiveOptions>> profile = stored.stream()
                    .filter(p -> p.name().equals(name)).findFirst();
            if (profile.isEmpty()) {
                out.println("Nie ma profilu „" + name + "”. Dostępne profile: photon-backup --list");
                return null;
            }
            profiles.add(new AgentProfile(name, profile.get().options()));
        }
        String problem = AgentProfile.validate(profiles);
        if (problem != null) {
            out.println(problem);
            return null;
        }
        return profiles;
    }

    /** Sprawdza, czy profile mogą działać razem (bez uruchamiania agenta) — np. przed włączeniem autostartu. */
    private static int check(List<String> profileNames, PrintStream out) throws IOException {
        if (loadProfiles(profileNames, out) == null) {
            return EXIT_ERROR;
        }
        out.println("OK");
        return EXIT_OK;
    }

    private static int runAgent(List<String> profileNames, AgentFiles files, PrintStream out) throws IOException {
        List<AgentProfile> profiles = loadProfiles(profileNames, out);
        if (profiles == null) {
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
        AgentLoop loop = AgentLoop.withDefaults(profiles, files, cache, AgentSettings.load(files),
                System.console() != null ? out : null);

        // Ikona w zasobniku systemowym (obok zegara) — gdy system ją obsługuje.
        Optional<AgentTray> tray = AgentTray.install(loop, settings.notifyOnFinish());
        BackupLog.append(files.logFile(), Instant.now(), tray.isPresent()
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
            RemoteFileSystemProvider.instance().closeAll();
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
            if (running) {
                out.println("PID: " + s.pid() + ", od: " + TIME.format(s.startedAt()));
            } else if (s.stoppedAt() != null) {
                out.println("Zatrzymany: " + TIME.format(s.stoppedAt()));
            }
            for (AgentStatus.ProfileStatus p : s.profiles()) {
                out.println();
                out.println("Profil: " + p.profile() + "  (" + p.source() + " → " + p.target() + ")");
                if (running) {
                    out.println("  Ostatnie sprawdzenie: " + (p.schedule().lastCheck() == null ? "—"
                            : TIME.format(p.schedule().lastCheck())) + ", następne: " + p.schedule().nextCheckText(TIME));
                    out.println("  Sesja: " + p.sessionText(TIME));
                    if (!p.stalled().isEmpty()) {
                        out.println("  Przesyłanie stoi (" + p.stalled().size() + "): " + p.stalled());
                    }
                }
                out.println("  Zarchiwizowano: " + p.archived() + ", miniatury: " + p.thumbnails()
                        + ", duplikaty do śmietnika: " + p.movedDuplicates()
                        + ", duplikaty do decyzji: " + p.pendingDuplicates().size()
                        + ", czeka (przesyłanie): " + p.waiting() + ", błędy: " + p.errors());
                if (p.lastProblem() != null) {
                    out.println("  Problem: " + p.lastProblem());
                }
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
                  photon-backup --profile "A" --profile "B"   kilka profili naraz — każdy ze swoim
                                                    folderem źródłowym i archiwum
                  photon-backup --check --profile "A" [--profile "B"]   sprawdza, czy profile mogą działać
                                                    razem (bez uruchamiania)
                  photon-backup --status            pokazuje stan agenta
                  photon-backup --stop              zatrzymuje działającego agenta
                  photon-backup --list              wypisuje profile „Archiwizacji telefonu”

                Folder danych: %s""".formatted(AppPaths.dataDir()));
    }

}
