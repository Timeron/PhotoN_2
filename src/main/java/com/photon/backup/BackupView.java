package com.photon.backup;

import com.photon.AppPaths;
import com.photon.duplicates.scan.HashCache;
import com.photon.phonearchive.PhoneArchiveOptions;
import com.photon.phonearchive.PhoneArchiveProfileStore;
import com.photon.phonearchive.PhoneArchiver;
import com.photon.profiles.Profile;
import com.photon.settings.SettingsService;
import com.photon.ui.Dialogs;
import com.photon.ui.FormSection;
import com.photon.ui.Texts;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignI;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.photon.ui.Forms.fieldLabel;
import static com.photon.ui.Forms.formGrid;

/**
 * Zakładka „Kopia zapasowa”: archiwizacja w tle według wybranego profilu „Archiwizacji telefonu”,
 * sprawdzana cyklicznie (domyślnie co minutę).
 */
public class BackupView extends BorderPane {

    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter LOG_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int MAX_LOG_ENTRIES = 500;

    private final PhoneArchiveProfileStore profiles;
    private final SettingsService settings;
    private final Duration fastInterval;
    private final Duration slowInterval;
    private final Duration idleAfter;
    private final Duration quietPeriod;
    private CheckPacer pacer;
    private final Clock clock;

    private final ComboBox<Profile<PhoneArchiveOptions>> profileCombo = new ComboBox<>();
    private final Label profileDescription = new Label();
    private final Label stateLabel = new Label();
    private final Label lastCheckLabel = new Label("—");
    private final Label nextCheckLabel = new Label("—");
    private final Label totalsLabel = new Label();
    private final ListView<String> log = new ListView<>();
    private final Label feedback = new Label();
    private final Button startButton = new Button();
    private final Button checkNowButton = new Button("Sprawdź teraz", new FontIcon(MaterialDesignR.REFRESH));
    private final Button removeDuplicatesButton = new Button();

    private volatile ScheduledExecutorService executor;
    private volatile ScheduledFuture<?> schedule;
    private ArchiveWatcher watcher;
    /** Flaga zatrzymania bieżącego uruchomienia (nowa przy każdym starcie). */
    private AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicBoolean cycleRunning = new AtomicBoolean();

    private int totalArchived;
    private int totalThumbnails;
    private int totalMovedDuplicates;
    private int totalErrors;
    private int waitingNow;

    public BackupView(PhoneArchiveProfileStore profiles, SettingsService settings) {
        this(profiles, settings, CheckPacer.FAST, CheckPacer.SLOW, CheckPacer.IDLE_AFTER, Duration.ofSeconds(30),
                Clock.systemDefaultZone());
    }

    /**
     * @param fastInterval co ile sprawdzać folder, gdy coś się dzieje
     * @param slowInterval co ile sprawdzać po dłuższej ciszy
     * @param idleAfter    po jakim czasie bez nowych plików przejść na {@code slowInterval}
     * @param quietPeriod  jak długo plik musi się nie zmieniać, by uznać go za w pełni przesłany
     */
    public BackupView(PhoneArchiveProfileStore profiles, SettingsService settings, Duration fastInterval,
                      Duration slowInterval, Duration idleAfter, Duration quietPeriod, Clock clock) {
        this.profiles = profiles;
        this.settings = settings;
        this.fastInterval = fastInterval;
        this.slowInterval = slowInterval;
        this.idleAfter = idleAfter;
        this.quietPeriod = quietPeriod;
        this.clock = clock;
        getStyleClass().add("backup-view");

        VBox content = new VBox(18, buildProfileSection(), buildStatusSection(), buildLogSection());
        content.getStyleClass().add("form");
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("form-scroll");
        setCenter(scroll);
        setBottom(buildActionBar());

        reloadProfiles();
        refreshState();
    }

    // =====================================================================
    // Publiczne API
    // =====================================================================

    /** Wczytuje profile „Archiwizacji telefonu” (mogły się zmienić). W trakcie pracy nic nie zmienia. */
    public void reloadProfiles() {
        if (isRunning()) {
            return;
        }
        Profile<PhoneArchiveOptions> current = profileCombo.getValue();
        List<Profile<PhoneArchiveOptions>> all;
        try {
            all = profiles.loadAll();
        } catch (IOException e) {
            all = List.of();
            showStatus("Nie udało się wczytać profili: " + e.getMessage(), true);
        }
        profileCombo.getItems().setAll(all);
        if (current != null) {
            all.stream().filter(p -> p.name().equals(current.name())).findFirst().ifPresent(profileCombo::setValue);
        }
        if (profileCombo.getValue() == null && !all.isEmpty()) {
            profileCombo.setValue(all.get(0));
        }
        refreshState();
    }

    public boolean isRunning() {
        return watcher != null;
    }

    public ComboBox<Profile<PhoneArchiveOptions>> profileCombo() {
        return profileCombo;
    }

    public List<String> logEntries() {
        return List.copyOf(log.getItems());
    }

    /** Uruchamia archiwizację w tle według wybranego profilu. */
    public void start() {
        if (isRunning()) {
            return;
        }
        Profile<PhoneArchiveOptions> profile = profileCombo.getValue();
        if (profile == null) {
            showStatus("Wybierz profil archiwizacji. Profile tworzysz w zakładce „Archiwizacja telefonu”.", true);
            return;
        }
        String problem = profile.options().validate();
        if (problem != null) {
            showStatus("Profil „" + profile.name() + "”: " + problem, true);
            return;
        }
        HashCache cache = HashCache.inCacheDir(settings.get().effectiveCacheDir()).load();
        watcher = new ArchiveWatcher(profile.name(), profile.options(), cache, quietPeriod, clock);
        AtomicBoolean stop = new AtomicBoolean();
        stopRequested = stop;
        pacer = new CheckPacer(fastInterval, slowInterval, idleAfter, clock);
        totalArchived = totalThumbnails = totalMovedDuplicates = totalErrors = waitingNow = 0;
        boolean lowPriority = settings.get().lowPriorityScan();
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "photon-backup-watcher");
            t.setDaemon(true);
            if (lowPriority) {
                t.setPriority(Thread.MIN_PRIORITY);
            }
            return t;
        });
        ArchiveWatcher current = watcher;
        CheckPacer currentPacer = pacer;
        schedule = executor.schedule(() -> runCycle(current, currentPacer, stop), 0, TimeUnit.MILLISECONDS);
        addLog("Start: profil „" + profile.name() + "”, sprawdzanie co " + describe(fastInterval)
                + " (po " + describe(idleAfter) + " bez nowych plików — co " + describe(slowInterval) + ").");
        showStatus("", false);
        refreshState();
    }

    /** Zatrzymuje archiwizację w tle. Trwający przebieg kończy się przy najbliższym pliku. */
    public void stop() {
        if (!isRunning()) {
            return;
        }
        stopRequested.set(true);
        schedule.cancel(false);
        executor.shutdown();
        addLog("Zatrzymano archiwizację w tle.");
        watcher = null;
        executor = null;
        schedule = null;
        pacer = null;
        nextCheckLabel.setText("—");
        refreshState();
    }

    /** Uruchamia przebieg od razu (gdy żaden nie trwa) — zaplanowany przebieg jest zastępowany. */
    public void checkNow() {
        if (isRunning() && !cycleRunning.get() && schedule != null && schedule.cancel(false)) {
            ArchiveWatcher current = watcher;
            CheckPacer currentPacer = pacer;
            AtomicBoolean stop = stopRequested;
            schedule = executor.schedule(() -> runCycle(current, currentPacer, stop), 0, TimeUnit.MILLISECONDS);
        }
    }

    // =====================================================================
    // Przebieg (wątek w tle)
    // =====================================================================

    private void runCycle(ArchiveWatcher current, CheckPacer currentPacer, AtomicBoolean stop) {
        if (stop.get()) {
            return;
        }
        cycleRunning.set(true);
        Platform.runLater(this::refreshState);
        ArchiveWatcher.CycleReport report;
        try {
            report = current.runCycle(stop::get);
        } catch (PhoneArchiver.CancelledException e) {
            report = null;
        } catch (RuntimeException e) {
            report = new ArchiveWatcher.CycleReport(clock.instant(), null, null, List.of(),
                    "Nieoczekiwany błąd: " + e.getMessage(), 0);
        } finally {
            cycleRunning.set(false);
        }
        // Następne sprawdzenie planujemy od razu w tym wątku — tempo zależy od tego, czy coś się pojawiło.
        Duration next = currentPacer.afterCycle(report != null && report.activity());
        CheckPacer.Change change = currentPacer.lastChange();
        if (!stop.get()) {
            try {
                ScheduledFuture<?> future = executor == null ? null
                        : executor.schedule(() -> runCycle(current, currentPacer, stop), next.toMillis(),
                        TimeUnit.MILLISECONDS);
                if (future != null) {
                    Platform.runLater(() -> {
                        if (watcher == current) {
                            schedule = future;
                        }
                    });
                }
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // zatrzymano w międzyczasie
            }
        }
        ArchiveWatcher.CycleReport result = report;
        Platform.runLater(() -> cycleFinished(current, result, next, change));
    }

    private void cycleFinished(ArchiveWatcher current, ArchiveWatcher.CycleReport report, Duration next,
                               CheckPacer.Change change) {
        if (report != null) {
            lastCheckLabel.setText(TIME.format(report.at()));
            waitingNow = report.waiting();
            totalArchived += report.archived();
            totalThumbnails += report.thumbnails();
            totalErrors += report.errors().size();
            if (report.movedDuplicates() != null) {
                totalMovedDuplicates += report.movedDuplicates().removed().size();
            }
            if (report.eventful()) {
                addLog(describe(report));
                report.errors().stream().limit(10).forEach(e -> addLog("    błąd: " + e));
            }
        }
        if (watcher == current) {
            nextCheckLabel.setText(TIME.format(clock.instant().plus(next)) + "  (co " + describe(next) + ")");
            if (change == CheckPacer.Change.SLOWED_DOWN) {
                addLog("Brak nowych plików od " + describe(idleAfter) + " — sprawdzanie co " + describe(slowInterval) + ".");
            } else if (change == CheckPacer.Change.SPED_UP) {
                addLog("Pojawiły się nowe pliki — sprawdzanie znowu co " + describe(fastInterval) + ".");
            }
        }
        refreshState();
    }

    static String describe(ArchiveWatcher.CycleReport report) {
        if (report.problem() != null) {
            return "Pominięto sprawdzenie: " + report.problem();
        }
        List<String> parts = new ArrayList<>();
        if (report.archived() > 0) {
            PhoneArchiver.Summary s = report.summary();
            parts.add("zarchiwizowano " + Texts.count(report.archived(), "plik", "pliki", "plików")
                    + " (" + Texts.formatBytes(s.archivedBytes()) + ") do " + s.dayFolder().getFileName());
        }
        if (report.thumbnails() > 0) {
            parts.add("miniatury: " + report.thumbnails());
        }
        if (report.movedDuplicates() != null && !report.movedDuplicates().removed().isEmpty()) {
            parts.add("przeniesiono duplikaty: " + report.movedDuplicates().removed().size());
        }
        if (!report.newDuplicates().isEmpty()) {
            parts.add("nowe duplikaty do decyzji: " + report.newDuplicates().size());
        }
        if (report.waiting() > 0) {
            parts.add("w trakcie przesyłania: " + report.waiting());
        }
        if (!report.errors().isEmpty()) {
            parts.add("błędy: " + report.errors().size());
        }
        String text = String.join(", ", parts);
        return text.isEmpty() ? "Bez zmian." : Character.toUpperCase(text.charAt(0)) + text.substring(1) + ".";
    }

    // =====================================================================
    // Duplikaty czekające na decyzję
    // =====================================================================

    private void removePendingDuplicates() {
        ArchiveWatcher current = watcher;
        if (current == null) {
            return;
        }
        List<PhoneArchiver.Duplicate> duplicates = current.pendingDuplicates();
        if (duplicates.isEmpty()) {
            refreshState();
            return;
        }
        Path target = current.options().target();
        List<String> details = new ArrayList<>();
        long bytes = 0;
        for (PhoneArchiver.Duplicate d : duplicates) {
            bytes += d.size();
            String existing = d.existing().startsWith(target) ? target.relativize(d.existing()).toString()
                    : d.existing().toString();
            details.add(d.source() + "   →   " + existing);
        }
        boolean toTrash = PhoneArchiver.trashSupported();
        String message = Texts.count(duplicates.size(), "plik", "pliki", "plików") + " (" + Texts.formatBytes(bytes)
                + ") z folderu źródłowego już jest w archiwum. Czy usunąć je z folderu źródłowego? "
                + "Kopie w archiwum pozostaną.\n\n"
                + (toTrash ? "Pliki trafią do kosza systemowego." : "Pliki zostaną usunięte trwale.");
        if (!Dialogs.confirmWithDetails(window(), "Duplikaty już są w archiwum", message, details,
                toTrash ? "Przenieś do kosza" : "Usuń trwale", true)) {
            return;
        }
        Task<PhoneArchiver.RemovalOutcome> task = new Task<>() {
            @Override
            protected PhoneArchiver.RemovalOutcome call() {
                return current.removePending(duplicates, toTrash);
            }
        };
        task.setOnSucceeded(e -> {
            PhoneArchiver.RemovalOutcome outcome = task.getValue();
            String text = (toTrash ? "Przeniesiono do kosza " : "Usunięto ")
                    + Texts.count(outcome.removed().size(), "duplikat", "duplikaty", "duplikatów") + "."
                    + (outcome.skippedProtected() > 0
                    ? " Pominięto w chronionych folderach „__”: " + outcome.skippedProtected() + "." : "");
            addLog(text);
            outcome.errors().stream().limit(10).forEach(err -> addLog("    błąd: " + err));
            showStatus(text, !outcome.errors().isEmpty());
            refreshState();
        });
        task.setOnFailed(e -> showStatus("Usuwanie duplikatów nie powiodło się: " + task.getException().getMessage(), true));
        removeDuplicatesButton.setDisable(true);
        Thread thread = new Thread(task, "photon-backup-remove-duplicates");
        thread.setDaemon(true);
        thread.start();
    }

    // =====================================================================
    // Widok
    // =====================================================================

    private Node buildProfileSection() {
        profileCombo.setMaxWidth(Double.MAX_VALUE);
        profileCombo.setPromptText("Brak profili — utwórz je w zakładce „Archiwizacja telefonu”");
        profileCombo.setCellFactory(list -> new ProfileCell());
        profileCombo.setButtonCell(new ProfileCell());
        profileCombo.valueProperty().addListener((obs, old, value) -> refreshState());

        GridPane grid = formGrid();
        grid.addRow(0, fieldLabel("Profil archiwizacji"), profileCombo);
        profileDescription.getStyleClass().add("field-hint");
        profileDescription.setWrapText(true);

        Label uploads = infoBanner("Folder źródłowy jest sprawdzany co " + describe(fastInterval) + ", a gdy przez "
                + describe(idleAfter) + " nic nowego się nie pojawi — co " + describe(slowInterval)
                + " (po pojawieniu się plików znowu co " + describe(fastInterval) + "). Pliki mogą "
                + "być jeszcze przesyłane (np. z internetu), dlatego plik trafia do archiwum dopiero wtedy, "
                + "gdy jego rozmiar i data zmiany nie zmieniły się przez co najmniej " + describe(quietPeriod)
                + " i żaden program nie trzyma go otwartego. Takie pliki czekają na kolejne sprawdzenie.");
        Label rules = infoBanner("Archiwizacja działa jak w zakładce „Archiwizacja telefonu”: nowe zdjęcia i filmy "
                + "trafiają do folderu z dzisiejszą datą, duplikaty są wykrywane, a miniatury tworzone zgodnie "
                + "z profilem. Duplikaty są przenoszone automatycznie, jeśli profil tak każe; jeśli profil każe je "
                + "usuwać — czekają na Twoje potwierdzenie (przycisk „Usuń zgłoszone duplikaty”).");
        return new FormSection(MaterialDesignP.PLAY_CIRCLE_OUTLINE,
                "Archiwizacja w tle",
                "Wybierz profil z zakładki „Archiwizacja telefonu” i uruchom automatyczne sprawdzanie.",
                grid, profileDescription, uploads, rules);
    }

    private Node buildStatusSection() {
        stateLabel.getStyleClass().add("backup-state");
        GridPane grid = formGrid();
        grid.addRow(0, fieldLabel("Stan"), stateLabel);
        grid.addRow(1, fieldLabel("Ostatnie sprawdzenie"), lastCheckLabel);
        grid.addRow(2, fieldLabel("Następne sprawdzenie"), nextCheckLabel);
        grid.addRow(3, fieldLabel("Od uruchomienia"), totalsLabel);
        totalsLabel.setWrapText(true);
        return new FormSection(MaterialDesignT.TIMER_OUTLINE, "Stan", "Bieżąca praca archiwizacji w tle.", grid);
    }

    private Node buildLogSection() {
        log.getStyleClass().add("backup-log");
        log.setPrefHeight(240);
        Label placeholder = new Label("Tu pojawią się zdarzenia archiwizacji.");
        placeholder.getStyleClass().add("list-placeholder");
        log.setPlaceholder(placeholder);
        return new FormSection(MaterialDesignI.INFORMATION_OUTLINE, "Dziennik",
                "Najnowsze zdarzenia na górze. Zapisywane też w pliku logs/kopia-zapasowa.log.", log);
    }

    private Node buildActionBar() {
        feedback.getStyleClass().add("action-feedback");
        feedback.setWrapText(true);
        VBox texts = new VBox(2, feedback);
        HBox.setHgrow(texts, Priority.ALWAYS);

        startButton.getStyleClass().add("button-primary");
        startButton.setOnAction(e -> {
            if (isRunning()) {
                stop();
            } else {
                start();
            }
        });
        checkNowButton.setOnAction(e -> checkNow());
        removeDuplicatesButton.getStyleClass().add("button-danger");
        removeDuplicatesButton.setGraphic(new FontIcon(MaterialDesignD.DELETE_OUTLINE));
        removeDuplicatesButton.setOnAction(e -> removePendingDuplicates());
        removeDuplicatesButton.managedProperty().bind(removeDuplicatesButton.visibleProperty());

        HBox bar = new HBox(12, texts, removeDuplicatesButton, checkNowButton, startButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("action-bar");
        BorderPane.setMargin(bar, new Insets(14, 24, 0, 0));
        return bar;
    }

    private void refreshState() {
        boolean running = isRunning();
        Profile<PhoneArchiveOptions> profile = profileCombo.getValue();
        profileCombo.setDisable(running);
        profileDescription.setText(profile == null ? "" : PhoneArchiveProfileStore.describe(profile.options())
                + "\nŹródło: " + profile.options().source() + "\nArchiwum: " + profile.options().target());

        if (running) {
            stateLabel.setText((cycleRunning.get() ? "Sprawdzanie…" : "Działa")
                    + " — profil „" + watcher.profileName() + "”");
        } else {
            stateLabel.setText("Zatrzymana");
        }
        stateLabel.pseudoClassStateChanged(ACTIVE, running);
        startButton.setText(running ? "Zatrzymaj" : "Uruchom");
        startButton.setGraphic(new FontIcon(running ? MaterialDesignS.STOP_CIRCLE_OUTLINE
                : MaterialDesignP.PLAY_CIRCLE_OUTLINE));
        startButton.setDisable(!running && profile == null);
        checkNowButton.setDisable(!running || cycleRunning.get());

        int pending = running ? watcher.pendingDuplicatesCountCached() : 0;
        removeDuplicatesButton.setText("Usuń zgłoszone duplikaty (" + pending + ")…");
        removeDuplicatesButton.setVisible(pending > 0);

        totalsLabel.setText("zarchiwizowano " + totalArchived + "  •  miniatury " + totalThumbnails
                + "  •  przeniesione duplikaty " + totalMovedDuplicates
                + "  •  duplikaty do decyzji " + pending
                + "  •  czeka (przesyłanie) " + waitingNow
                + "  •  błędy " + totalErrors);
    }

    private void addLog(String text) {
        Instant now = clock.instant();
        log.getItems().add(0, TIME.format(now) + "  " + text);
        if (log.getItems().size() > MAX_LOG_ENTRIES) {
            log.getItems().remove(MAX_LOG_ENTRIES, log.getItems().size());
        }
        try {
            Path file = AppPaths.logsDir().resolve("kopia-zapasowa.log");
            Files.createDirectories(file.getParent());
            Files.writeString(file, LOG_TIME.format(now) + "\t" + text + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Nie udało się zapisać dziennika kopii zapasowej: " + e.getMessage());
        }
    }

    private void showStatus(String text, boolean error) {
        feedback.setText(text);
        feedback.pseudoClassStateChanged(ERROR, error);
    }

    private static String describe(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds > 0 && seconds % 60 == 0) {
            long minutes = seconds / 60;
            return minutes == 1 ? "minutę" : Texts.count((int) minutes, "minutę", "minuty", "minut");
        }
        return Texts.count((int) seconds, "sekundę", "sekundy", "sekund");
    }

    private static Label infoBanner(String text) {
        Label label = new Label(text, new FontIcon(MaterialDesignI.INFORMATION_OUTLINE));
        label.getStyleClass().add("info-banner");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private javafx.stage.Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private static final class ProfileCell extends ListCell<Profile<PhoneArchiveOptions>> {
        @Override
        protected void updateItem(Profile<PhoneArchiveOptions> item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : item.name());
        }
    }
}
