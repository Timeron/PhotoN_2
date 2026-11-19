package com.photon.backup;

import com.photon.phonearchive.PhoneArchiveOptions;
import com.photon.phonearchive.PhoneArchiveProfileStore;
import com.photon.phonearchive.SourceDuplicates;
import com.photon.profiles.Profile;
import com.photon.ui.Dialogs;
import com.photon.ui.FormSection;
import com.photon.ui.Texts;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.cell.CheckBoxListCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.photon.ui.Forms.fieldLabel;
import static com.photon.ui.Forms.formGrid;
import static com.photon.ui.Forms.infoBanner;

/**
 * Zakładka „Kopia zapasowa”: podgląd i sterowanie agentem archiwizacji w tle ({@link BackupAgent}).
 * Agent to osobny, lekki proces — działa także po zamknięciu PhotoN i może pilnować kilku profili naraz.
 * Zakładka odczytuje jego stan ({@code backup/status.json}) i dziennik, a polecenia wysyła przez pliki
 * w {@code backup/commands/}.
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

    private final ListView<Profile<PhoneArchiveOptions>> profileList = new ListView<>();
    /** Zaznaczenie profili (po nazwie) — przetrwa ponowne wczytanie listy. */
    private final Map<String, BooleanProperty> checked = new HashMap<>();
    private final Label profileDescription = new Label();
    private final Label stateLabel = new Label();
    private final Label sinceLabel = new Label("—");
    private final VBox profileStates = new VBox(14);
    private final ListView<String> log = new ListView<>();
    private final Label feedback = new Label();
    private final ComboBox<Integer> safetyCheck = new ComboBox<>();
    private final ComboBox<Integer> quietPeriod = new ComboBox<>();
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
        List<Profile<PhoneArchiveOptions>> all;
        try {
            all = profiles.loadAll();
        } catch (IOException e) {
            all = List.of();
            showStatus("Nie udało się wczytać profili: " + e.getMessage(), true);
        }
        profileList.getItems().setAll(all);
        if (selectedProfiles().isEmpty() && !all.isEmpty()) {
            selection(all.get(0).name()).set(true); // domyślnie pierwszy profil
        }
        refreshButtons();
    }

    public boolean isRunning() {
        return running;
    }

    /** Czy wyboru profili nie można zmienić (agent działa albo właśnie startuje). */
    public boolean isProfileSelectionLocked() {
        return profileList.isDisabled();
    }

    /** Zaznacza dokładnie te profile (pozostałe odznacza). */
    public void selectProfiles(List<String> names) {
        checked.values().forEach(p -> p.set(false));
        names.forEach(n -> selection(n).set(true));
        refreshButtons();
    }

    /** Zaznaczone profile, w kolejności listy. */
    public List<String> selectedProfiles() {
        return profileList.getItems().stream().map(Profile::name)
                .filter(n -> checked.containsKey(n) && checked.get(n).get()).toList();
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
        List<AgentProfile> chosen = profileList.getItems().stream()
                .filter(p -> selectedProfiles().contains(p.name()))
                .map(p -> new AgentProfile(p.name(), p.options())).toList();
        if (chosen.isEmpty()) {
            showStatus("Zaznacz co najmniej jeden profil. Profile tworzysz w zakładce „Archiwizacja telefonu”.", true);
            return;
        }
        String problem = AgentProfile.validate(chosen);
        if (problem != null) {
            showStatus(problem, true);
            return;
        }
        try {
            control.start(chosen.stream().map(AgentProfile::name).toList());
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
        List<String> details = new ArrayList<>();
        List<Path> paths = new ArrayList<>();
        long bytes = 0;
        for (AgentStatus.ProfileStatus profile : status.profiles()) {
            Path target = profile.target();
            for (AgentStatus.PendingDuplicate d : profile.pendingDuplicates()) {
                bytes += d.size();
                paths.add(d.source());
                String existing = target != null && d.existing().startsWith(target)
                        ? target.relativize(d.existing()).toString() : d.existing().toString();
                details.add((status.profiles().size() > 1 ? "[" + profile.profile() + "] " : "")
                        + d.source() + "   →   " + existing);
            }
        }
        boolean toTrash = SourceDuplicates.trashSupported();
        String message = Texts.count(paths.size(), "plik", "pliki", "plików") + " (" + Texts.formatBytes(bytes)
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

    private BooleanProperty selection(String name) {
        return checked.computeIfAbsent(name, n -> {
            BooleanProperty property = new SimpleBooleanProperty(false);
            property.addListener((obs, old, value) -> refreshButtons());
            return property;
        });
    }

    private Node buildProfileSection() {
        profileList.getStyleClass().add("folder-list");
        profileList.setPrefHeight(130);
        Label placeholder = new Label("Brak profili — utwórz je w zakładce „Archiwizacja telefonu”.");
        placeholder.getStyleClass().add("list-placeholder");
        profileList.setPlaceholder(placeholder);
        profileList.setCellFactory(CheckBoxListCell.forListView(p -> selection(p.name()), new StringConverter<>() {
            @Override
            public String toString(Profile<PhoneArchiveOptions> p) {
                return p == null ? "" : p.name() + "   —   " + p.options().source() + "  →  " + p.options().target();
            }

            @Override
            public Profile<PhoneArchiveOptions> fromString(String text) {
                return null;
            }
        }));

        GridPane grid = formGrid();
        grid.addRow(0, fieldLabel("Profile archiwizacji"), profileList);
        profileDescription.getStyleClass().add("field-hint");
        profileDescription.setWrapText(true);

        Label agent = infoBanner("Archiwizację w tle wykonuje osobny, lekki program (agent) — działa także po "
                + "zamknięciu PhotoN. Zaznacz jeden albo kilka profili: agent pilnuje wtedy kilku folderów naraz, "
                + "każdy z jego własnym archiwum. Z konsoli: photon-backup.bat --profile \"A\" --profile \"B\" "
                + "(oraz --status, --stop, --list).");
        safetyCheck.getItems().setAll(AgentSettings.CHOICES);
        safetyCheck.setMaxWidth(Double.MAX_VALUE);
        safetyCheck.setConverter(converter(AgentSettings::label));
        safetyCheck.setValue(AgentSettings.load(control.files()).safetyCheckMinutes());
        safetyCheck.valueProperty().addListener((obs, old, minutes) -> {
            if (minutes != null && !minutes.equals(old)) {
                saveSettings(AgentSettings.load(control.files()).withSafetyCheckMinutes(minutes),
                        "Zapisano odstęp kontrolnego sprawdzania.");
            }
        });
        grid.addRow(1, fieldLabel("Sprawdzanie kontrolne"), safetyCheck);

        quietPeriod.getItems().setAll(AgentSettings.QUIET_CHOICES);
        quietPeriod.setMaxWidth(Double.MAX_VALUE);
        quietPeriod.setConverter(converter(AgentSettings::quietLabel));
        quietPeriod.setValue(AgentSettings.load(control.files()).quietSeconds());
        quietPeriod.valueProperty().addListener((obs, old, seconds) -> {
            if (seconds != null && !seconds.equals(old)) {
                saveSettings(AgentSettings.load(control.files()).withQuietSeconds(seconds),
                        "Zapisano czas oczekiwania na koniec przesyłania.");
            }
        });
        grid.addRow(2, fieldLabel("Plik przesłany, gdy bez zmian przez"), quietPeriod);

        Label pace = infoBanner("Nowy plik budzi agenta od razu (powiadomienia Windows). Gdy nic się nie dzieje, agent "
                + "śpi i nie dotyka dysków (mogą zasnąć) — najwyżej sprawdza foldery kontrolnie, w odstępie wybranym "
                + "powyżej, na wypadek zgubionego powiadomienia. Pliki w trakcie przesyłania sprawdza co 15 sekund, aż "
                + "przez wybrany czas nie zmienią rozmiaru ani daty i będą kompletne (zdjęcia i filmy są sprawdzane, "
                + "czy nie są ucięte — przerwane przesyłanie nie trafi do archiwum). Folder na serwerze FTP/SFTP "
                + "i dyski sieciowe bez powiadomień: co 15 sekund, a po 15 minutach ciszy w odstępie kontrolnym.");
        Label rules = infoBanner("Archiwizacja działa jak w zakładce „Archiwizacja telefonu”: nowe zdjęcia i filmy "
                + "trafiają do folderu z dzisiejszą datą, a miniatury są tworzone zgodnie z profilem. Duplikaty trafiają "
                + "do śmietnika, jeśli profil tak każe; jeśli profil każe je usuwać — czekają na Twoje potwierdzenie.");
        return new FormSection(MaterialDesignP.PLAY_CIRCLE_OUTLINE,
                "Archiwizacja w tle",
                "Zaznacz profile z zakładki „Archiwizacja telefonu” i uruchom agenta.",
                grid, profileDescription, agent, pace, rules);
    }

    private static StringConverter<Integer> converter(java.util.function.IntFunction<String> label) {
        return new StringConverter<>() {
            @Override
            public String toString(Integer value) {
                return value == null ? "" : label.apply(value);
            }

            @Override
            public Integer fromString(String text) {
                return null;
            }
        };
    }

    private void saveSettings(AgentSettings settings, String savedMessage) {
        try {
            settings.save(control.files());
            showStatus(control.isRunning()
                    ? "Zapisano. Zmiana zacznie obowiązywać po ponownym uruchomieniu agenta." : savedMessage, false);
        } catch (IOException e) {
            showStatus("Nie udało się zapisać ustawienia: " + e.getMessage(), true);
        }
    }

    private Node buildStatusSection() {
        stateLabel.getStyleClass().add("backup-state");
        GridPane grid = formGrid();
        grid.addRow(0, fieldLabel("Stan"), stateLabel);
        grid.addRow(1, fieldLabel("Działa od"), sinceLabel);
        return new FormSection(MaterialDesignT.TIMER_OUTLINE, "Stan agenta",
                "Odświeżany co sekundę, gdy ta zakładka jest otwarta.", grid, profileStates);
    }

    /** Stan jednego profilu: sprawdzenia, liczniki, sesja, problemy. */
    private Node profileState(AgentStatus.ProfileStatus p, boolean live) {
        Label title = new Label(p.profile() + "   —   " + p.source() + "  →  " + p.target());
        title.getStyleClass().add("profile-state-title");
        title.setWrapText(true);
        GridPane grid = formGrid();
        int row = 0;
        grid.addRow(row++, fieldLabel("Ostatnie sprawdzenie"),
                wrapped(p.schedule().lastCheck() != null ? TIME.format(p.schedule().lastCheck()) : "—"));
        grid.addRow(row++, fieldLabel("Następne sprawdzenie"), wrapped(live ? p.schedule().nextCheckText(TIME) : "—"));
        grid.addRow(row++, fieldLabel("Powiadomienia"), wrapped(!live ? "—" : p.schedule().notifications()
                ? "aktywne — nowy plik budzi agenta od razu" : "niedostępne — tylko sprawdzanie cykliczne"));
        grid.addRow(row++, fieldLabel("Od uruchomienia"), wrapped("zarchiwizowano " + p.archived()
                + "  •  miniatury " + p.thumbnails() + "  •  duplikaty do śmietnika " + p.movedDuplicates()
                + "  •  duplikaty do decyzji " + p.pendingDuplicates().size()
                + "  •  czeka (przesyłanie) " + p.waiting() + "  •  błędy " + p.errors()));
        grid.addRow(row, fieldLabel("Sesja"), wrapped(live || p.session() == null ? p.sessionText(TIME)
                : p.lastSession() == null ? "—" : "ostatnia: " + p.lastSession()));
        VBox box = new VBox(8, title, grid);
        box.getStyleClass().add("profile-state");
        if (live && p.lastProblem() != null) {
            box.getChildren().add(warning(p.lastProblem()));
        }
        if (live && !p.stalled().isEmpty()) {
            box.getChildren().add(warning("Przesyłanie stoi od ponad 15 minut (" + p.stalled().size() + "): "
                    + p.stalled().stream().limit(8).map(f -> String.valueOf(f.getFileName()))
                    .collect(Collectors.joining(", ")) + (p.stalled().size() > 8 ? ", …" : "")
                    + ". Pliki są niekompletne — wyślij je z telefonu ponownie; zostają w folderze archiwizowanym."));
        }
        return box;
    }

    private static Label wrapped(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        return label;
    }

    private static Label warning(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("warning-banner");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
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
                    + (s != null ? " — " + (s.profiles().size() == 1 ? "profil " : "profile ") + s.profileNames()
                    + ", PID " + s.pid() : ""));
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
        List<Node> states = new ArrayList<>();
        if (s != null) {
            s.profiles().forEach(p -> states.add(profileState(p, live)));
        }
        profileStates.getChildren().setAll(states);
    }

    private void refreshLog() {
        List<String> lines = control.logTail(LOG_LINES);
        if (!lines.equals(log.getItems())) {
            log.getItems().setAll(lines);
        }
    }

    private void refreshButtons() {
        // Działający agent: zaznaczone są jego profile.
        if (running && status != null) {
            Set<String> active = new LinkedHashSet<>(status.profiles().stream()
                    .map(AgentStatus.ProfileStatus::profile).toList());
            if (!active.equals(new LinkedHashSet<>(selectedProfiles()))) {
                checked.forEach((name, property) -> property.set(active.contains(name)));
                active.forEach(n -> selection(n).set(true));
            }
        }
        profileList.setDisable(running || startRequestedAt != null);
        List<String> selected = selectedProfiles();
        profileDescription.setText(selected.isEmpty() ? "Nie zaznaczono żadnego profilu."
                : selected.size() == 1 ? "Zaznaczony profil: " + selected.get(0)
                : "Zaznaczone profile (" + selected.size() + "): " + String.join(", ", selected)
                + " — jeden agent pilnuje wszystkich folderów naraz.");

        startButton.setText(running ? "Zatrzymaj" : "Uruchom");
        startButton.setGraphic(new FontIcon(running ? MaterialDesignS.STOP_CIRCLE_OUTLINE
                : MaterialDesignP.PLAY_CIRCLE_OUTLINE));
        startButton.setDisable(running ? stopRequested : (selected.isEmpty() || startRequestedAt != null));
        checkNowButton.setDisable(!running || stopRequested);

        int pending = running && status != null ? status.pendingDuplicates().size() : 0;
        removeDuplicatesButton.setText("Usuń zgłoszone duplikaty (" + pending + ")…");
        removeDuplicatesButton.setVisible(pending > 0);
    }

    private void showStatus(String text, boolean error) {
        feedback.setText(text);
        feedback.pseudoClassStateChanged(ERROR, error);
    }

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }
}
