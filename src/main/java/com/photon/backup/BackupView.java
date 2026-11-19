package com.photon.backup;

import com.photon.phonearchive.PhoneArchiveOptions;
import com.photon.phonearchive.PhoneArchiveProfileStore;
import com.photon.phonearchive.PhoneArchiver;
import com.photon.profiles.Profile;
import com.photon.ui.Dialogs;
import com.photon.ui.FormSection;
import com.photon.ui.Texts;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
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
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignI;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignR;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;
import org.kordamp.ikonli.materialdesign2.MaterialDesignT;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.photon.ui.Forms.fieldLabel;
import static com.photon.ui.Forms.formGrid;

/**
 * Zakładka „Kopia zapasowa”: podgląd i sterowanie agentem archiwizacji w tle ({@link BackupAgent}).
 * Agent to osobny, lekki proces — działa także po zamknięciu PhotoN. Zakładka odczytuje jego stan
 * ({@code backup/status.json}) i dziennik, a polecenia wysyła przez pliki w {@code backup/commands/}.
 */
public class BackupView extends BorderPane {

    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");
    private static final PseudoClass ACTIVE = PseudoClass.getPseudoClass("active");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("d.MM.yyyy HH:mm").withZone(ZoneId.systemDefault());
    private static final int LOG_LINES = 300;
    /** Ile czekać na zgłoszenie się uruchomionego agenta, zanim pokażemy błąd. */
    private static final java.time.Duration START_TIMEOUT = java.time.Duration.ofSeconds(20);

    private final PhoneArchiveProfileStore profiles;
    private final AgentControl control;

    private final ComboBox<Profile<PhoneArchiveOptions>> profileCombo = new ComboBox<>();
    private final Label profileDescription = new Label();
    private final Label stateLabel = new Label();
    private final Label sinceLabel = new Label("—");
    private final Label lastCheckLabel = new Label("—");
    private final Label nextCheckLabel = new Label("—");
    private final Label notificationsLabel = new Label("—");
    private final Label totalsLabel = new Label("—");
    private final Label problemLabel = new Label();
    private final ListView<String> log = new ListView<>();
    private final Label feedback = new Label();
    private final Button startButton = new Button();
    private final Button checkNowButton = new Button("Sprawdź teraz", new FontIcon(MaterialDesignR.REFRESH));
    private final Button removeDuplicatesButton = new Button();
    private final Timeline refresher = new Timeline(new KeyFrame(Duration.seconds(1), e -> refresh()));

    private boolean running;
    private AgentStatus status;
    private Instant startRequestedAt;
    private boolean stopRequested;

    public BackupView(PhoneArchiveProfileStore profiles, AgentControl control) {
        this.profiles = profiles;
        this.control = control;
        getStyleClass().add("backup-view");

        VBox content = new VBox(18, buildProfileSection(), buildStatusSection(), buildLogSection());
        content.getStyleClass().add("form");
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("form-scroll");
        setCenter(scroll);
        setBottom(buildActionBar());

        // Odświeżanie tylko wtedy, gdy zakładka jest widoczna.
        refresher.setCycleCount(Timeline.INDEFINITE);
        sceneProperty().addListener((obs, old, scene) -> {
            if (scene == null) {
                refresher.stop();
            } else {
                refresh();
                refresher.play();
            }
        });

        reloadProfiles();
        refresh();
    }

    // =====================================================================
    // Publiczne API
    // =====================================================================

    /** Wczytuje profile „Archiwizacji telefonu” (mogły się zmienić). */
    public void reloadProfiles() {
        Profile<PhoneArchiveOptions> current = profileCombo.getValue();
        List<Profile<PhoneArchiveOptions>> all;
        try {
            all = profiles.loadAll();
        } catch (IOException e) {
            all = List.of();
            showStatus("Nie udało się wczytać profili: " + e.getMessage(), true);
        }
        profileCombo.getItems().setAll(all);
        String wanted = running && status != null ? status.profile() : current == null ? null : current.name();
        if (wanted != null) {
            all.stream().filter(p -> p.name().equals(wanted)).findFirst().ifPresent(profileCombo::setValue);
        }
        if (profileCombo.getValue() == null && !all.isEmpty()) {
            profileCombo.setValue(all.get(0));
        }
        refreshButtons();
    }

    public boolean isRunning() {
        return running;
    }

    public ComboBox<Profile<PhoneArchiveOptions>> profileCombo() {
        return profileCombo;
    }

    public List<String> logEntries() {
        return List.copyOf(log.getItems());
    }

    /** Odczytuje stan agenta i dziennik (wywoływane co sekundę, gdy zakładka jest widoczna). */
    public void refresh() {
        running = control.isRunning();
        status = control.readStatus().orElse(status);
        if (running) {
            startRequestedAt = null;
        } else {
            stopRequested = false;
            if (startRequestedAt != null && Instant.now().isAfter(startRequestedAt.plus(START_TIMEOUT))) {
                startRequestedAt = null;
                showStatus("Agent nie uruchomił się. Szczegóły: " + control.files().consoleFile(), true);
            }
        }
        refreshStatus();
        refreshLog();
        refreshButtons();
    }

    // =====================================================================
    // Akcje
    // =====================================================================

    private void start() {
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
        try {
            control.start(profile.name());
            startRequestedAt = Instant.now();
            showStatus("Uruchamianie agenta… Będzie działał także po zamknięciu PhotoN.", false);
        } catch (IOException e) {
            showStatus("Nie udało się uruchomić agenta: " + e.getMessage(), true);
        }
        refreshStatus();
        refreshButtons();
    }

    private void stop() {
        send(AgentCommand.of(AgentCommand.Type.STOP), "Zatrzymywanie agenta… (kończy bieżące sprawdzenie)");
        stopRequested = true;
        refreshButtons();
    }

    private void checkNow() {
        send(AgentCommand.of(AgentCommand.Type.CHECK_NOW), "Wysłano polecenie „Sprawdź teraz”.");
    }

    private void removePendingDuplicates() {
        if (status == null || status.pendingDuplicates().isEmpty()) {
            return;
        }
        List<AgentStatus.PendingDuplicate> duplicates = status.pendingDuplicates();
        Path target = status.target();
        List<String> details = new ArrayList<>();
        List<Path> paths = new ArrayList<>();
        long bytes = 0;
        for (AgentStatus.PendingDuplicate d : duplicates) {
            bytes += d.size();
            paths.add(d.source());
            String existing = target != null && d.existing().startsWith(target)
                    ? target.relativize(d.existing()).toString() : d.existing().toString();
            details.add(d.source() + "   →   " + existing);
        }
        boolean toTrash = PhoneArchiver.trashSupported();
        String message = Texts.count(duplicates.size(), "plik", "pliki", "plików") + " (" + Texts.formatBytes(bytes)
                + ") z folderu źródłowego już jest w archiwum. Czy usunąć je z folderu źródłowego? "
                + "Kopie w archiwum pozostaną.\n\n"
                + (toTrash ? "Pliki trafią do kosza systemowego." : "Pliki zostaną usunięte trwale.");
        if (Dialogs.confirmWithDetails(window(), "Duplikaty już są w archiwum", message, details,
                toTrash ? "Przenieś do kosza" : "Usuń trwale", true)) {
            send(AgentCommand.removeDuplicates(paths, toTrash), "Wysłano polecenie usunięcia duplikatów.");
        }
    }

    private void send(AgentCommand command, String message) {
        try {
            control.send(command);
            showStatus(message, false);
        } catch (IOException e) {
            showStatus("Nie udało się wysłać polecenia: " + e.getMessage(), true);
        }
    }

    // =====================================================================
    // Widok
    // =====================================================================

    private Node buildProfileSection() {
        profileCombo.setMaxWidth(Double.MAX_VALUE);
        profileCombo.setPromptText("Brak profili — utwórz je w zakładce „Archiwizacja telefonu”");
        profileCombo.setCellFactory(list -> new ProfileCell());
        profileCombo.setButtonCell(new ProfileCell());
        profileCombo.valueProperty().addListener((obs, old, value) -> refreshButtons());

        GridPane grid = formGrid();
        grid.addRow(0, fieldLabel("Profil archiwizacji"), profileCombo);
        profileDescription.getStyleClass().add("field-hint");
        profileDescription.setWrapText(true);

        Label agent = infoBanner("Archiwizację w tle wykonuje osobny, lekki program (agent) — działa także po "
                + "zamknięciu PhotoN. Tutaj go uruchamiasz, zatrzymujesz i podglądasz. Można go też uruchomić "
                + "z konsoli: photon-backup.bat --profile \"NAZWA\" (oraz --status, --stop, --list).");
        Label pace = infoBanner("Agent sprawdza folder co 15 sekund, a po 15 minutach bez nowych plików co 15 minut. "
                + "Nowy plik budzi go od razu (powiadomienia systemowe). Pliki w trakcie przesyłania czekają, aż przez "
                + "30 sekund nie zmienią rozmiaru ani daty i żaden program nie będzie ich zapisywał.");
        Label rules = infoBanner("Archiwizacja działa jak w zakładce „Archiwizacja telefonu”: nowe zdjęcia i filmy "
                + "trafiają do folderu z dzisiejszą datą, a miniatury są tworzone zgodnie z profilem. Duplikaty trafiają "
                + "do śmietnika, jeśli profil tak każe; jeśli profil każe je usuwać — czekają na Twoje potwierdzenie.");
        return new FormSection(MaterialDesignP.PLAY_CIRCLE_OUTLINE,
                "Archiwizacja w tle",
                "Wybierz profil z zakładki „Archiwizacja telefonu” i uruchom agenta.",
                grid, profileDescription, agent, pace, rules);
    }

    private Node buildStatusSection() {
        stateLabel.getStyleClass().add("backup-state");
        problemLabel.getStyleClass().add("warning-banner");
        problemLabel.setWrapText(true);
        problemLabel.setMaxWidth(Double.MAX_VALUE);
        problemLabel.managedProperty().bind(problemLabel.textProperty().isNotEmpty());
        problemLabel.visibleProperty().bind(problemLabel.managedProperty());
        totalsLabel.setWrapText(true);

        GridPane grid = formGrid();
        grid.addRow(0, fieldLabel("Stan"), stateLabel);
        grid.addRow(1, fieldLabel("Działa od"), sinceLabel);
        grid.addRow(2, fieldLabel("Ostatnie sprawdzenie"), lastCheckLabel);
        grid.addRow(3, fieldLabel("Następne sprawdzenie"), nextCheckLabel);
        grid.addRow(4, fieldLabel("Powiadomienia"), notificationsLabel);
        grid.addRow(5, fieldLabel("Od uruchomienia"), totalsLabel);
        return new FormSection(MaterialDesignT.TIMER_OUTLINE, "Stan agenta",
                "Odświeżany co sekundę, gdy ta zakładka jest otwarta.", grid, problemLabel);
    }

    private Node buildLogSection() {
        log.getStyleClass().add("backup-log");
        log.setPrefHeight(260);
        Label placeholder = new Label("Tu pojawią się zdarzenia archiwizacji.");
        placeholder.getStyleClass().add("list-placeholder");
        log.setPlaceholder(placeholder);
        return new FormSection(MaterialDesignI.INFORMATION_OUTLINE, "Dziennik",
                "Najnowsze zdarzenia na górze. Plik: " + control.files().logFile(), log);
    }

    private Node buildActionBar() {
        feedback.getStyleClass().add("action-feedback");
        feedback.setWrapText(true);
        VBox texts = new VBox(2, feedback);
        HBox.setHgrow(texts, Priority.ALWAYS);

        startButton.getStyleClass().add("button-primary");
        startButton.setOnAction(e -> {
            if (running) {
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

    private void refreshStatus() {
        AgentStatus s = status;
        if (running) {
            stateLabel.setText((s != null && s.checking() ? "Sprawdzanie…" : "Działa")
                    + (s != null ? " — profil „" + s.profile() + "”, PID " + s.pid() : ""));
        } else if (startRequestedAt != null) {
            stateLabel.setText("Uruchamianie…");
        } else {
            stateLabel.setText("Zatrzymany" + (s != null && s.stoppedAt() != null
                    ? " (od " + DATE_TIME.format(s.stoppedAt()) + ")"
                    : s != null && s.state() == AgentStatus.State.RUNNING ? " (zakończył się niespodziewanie)" : ""));
        }
        stateLabel.pseudoClassStateChanged(ACTIVE, running);

        boolean live = running && s != null;
        sinceLabel.setText(live && s.startedAt() != null ? DATE_TIME.format(s.startedAt()) : "—");
        lastCheckLabel.setText(s != null && s.lastCheck() != null ? TIME.format(s.lastCheck()) : "—");
        nextCheckLabel.setText(live && s.nextCheck() != null
                ? TIME.format(s.nextCheck()) + "  (co " + AgentLoop.describe(java.time.Duration.ofSeconds(s.intervalSeconds()))
                + (s.slowMode() ? " — brak nowych plików od 15 minut" : "") + ")"
                : "—");
        notificationsLabel.setText(!live ? "—" : s.notifications()
                ? "aktywne — nowy plik budzi agenta od razu"
                : "niedostępne — tylko sprawdzanie cykliczne");
        totalsLabel.setText(s == null ? "—" : "zarchiwizowano " + s.archived() + "  •  miniatury " + s.thumbnails()
                + "  •  duplikaty do śmietnika " + s.movedDuplicates()
                + "  •  duplikaty do decyzji " + s.pendingDuplicates().size()
                + "  •  czeka (przesyłanie) " + s.waiting()
                + "  •  błędy " + s.errors());
        problemLabel.setText(live && s.lastProblem() != null ? s.lastProblem() : "");
    }

    private void refreshLog() {
        List<String> lines = control.logTail(LOG_LINES);
        if (!lines.equals(log.getItems())) {
            log.getItems().setAll(lines);
        }
    }

    private void refreshButtons() {
        Profile<PhoneArchiveOptions> profile = profileCombo.getValue();
        if (running && status != null && (profile == null || !Objects.equals(profile.name(), status.profile()))) {
            profileCombo.getItems().stream().filter(p -> p.name().equals(status.profile())).findFirst()
                    .ifPresent(profileCombo::setValue);
            profile = profileCombo.getValue();
        }
        profileCombo.setDisable(running || startRequestedAt != null);
        profileDescription.setText(profile == null ? "" : PhoneArchiveProfileStore.describe(profile.options())
                + "\nŹródło: " + profile.options().source() + "\nArchiwum: " + profile.options().target());

        startButton.setText(running ? "Zatrzymaj" : "Uruchom");
        startButton.setGraphic(new FontIcon(running ? MaterialDesignS.STOP_CIRCLE_OUTLINE
                : MaterialDesignP.PLAY_CIRCLE_OUTLINE));
        startButton.setDisable(running ? stopRequested : (profile == null || startRequestedAt != null));
        checkNowButton.setDisable(!running || stopRequested);

        int pending = running && status != null ? status.pendingDuplicates().size() : 0;
        removeDuplicatesButton.setText("Usuń zgłoszone duplikaty (" + pending + ")…");
        removeDuplicatesButton.setVisible(pending > 0);
    }

    private void showStatus(String text, boolean error) {
        feedback.setText(text);
        feedback.pseudoClassStateChanged(ERROR, error);
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
