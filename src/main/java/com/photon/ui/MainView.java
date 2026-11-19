package com.photon.ui;

import com.photon.help.HelpView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.photon.PhotonApp;
import com.photon.backup.BackupView;
import com.photon.duplicates.DuplicateProfileSource;
import com.photon.duplicates.DuplicateProfileStore;
import com.photon.duplicates.DuplicatesModule;
import com.photon.phonearchive.PhoneArchiveModule;
import com.photon.phonearchive.PhoneArchiveProfileStore;
import com.photon.profiles.ProfileItem;
import com.photon.profiles.ProfilesView;
import com.photon.settings.SettingsService;
import com.photon.settings.SettingsView;
import com.photon.thumbnails.ThumbnailProfileStore;
import com.photon.thumbnails.ThumbnailsModule;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.shape.Circle;

import java.util.List;
import com.photon.backup.AgentControl;

/**
 * Główny układ okna: menu boczne | przestrzeń danych, pasek statusu na dole.
 */
public class MainView extends BorderPane {

    private final Sidebar sidebar = new Sidebar();
    private final ContentArea contentArea = new ContentArea();

    private final DuplicateProfileStore duplicateProfileStore = DuplicateProfileStore.createDefault();
    private final ThumbnailProfileStore thumbnailProfileStore = ThumbnailProfileStore.createDefault();
    private final PhoneArchiveProfileStore phoneArchiveProfileStore = PhoneArchiveProfileStore.createDefault();
    private final SettingsService settings;

    /** Widoki modułów tworzone przy pierwszym użyciu i zachowywane (formularz pamięta stan). */
    private DuplicatesModule duplicatesModule;
    private ThumbnailsModule thumbnailsModule;
    private PhoneArchiveModule phoneArchiveModule;
    private BackupView backupView;
    private ProfilesView profilesView;
    private SettingsView settingsView;
    private HelpView helpView;

    public MainView(SettingsService settings) {
        this.settings = settings;
        getStyleClass().add("main-view");
        setLeft(sidebar);
        setCenter(contentArea);
        setBottom(createStatusBar());

        sidebar.selectedSectionProperty().addListener((obs, old, section) -> {
            if (section != null) {
                showSection(section);
            }
        });
        // F1 — instrukcja z każdego miejsca aplikacji.
        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.F1) {
                sidebar.select(Section.HELP);
                e.consume();
            }
        });
    }

    private void showSection(Section section) {
        switch (section) {
            case DUPLICATES -> {
                DuplicatesModule module = duplicatesModule();
                module.form().reloadProfiles();
                contentArea.show("Szukaj duplikatów",
                        "Wskaż, gdzie i jak szukać powtarzających się zdjęć oraz filmów.",
                        module);
            }
            case THUMBNAILS -> {
                ThumbnailsModule module = thumbnailsModule();
                module.form().reloadProfiles();
                contentArea.show("Miniaturyzacja",
                        "Twórz pomniejszone kopie zdjęć z zachowaniem metadanych i nazw plików.",
                        module);
            }
            case PHONE_ARCHIVE -> {
                PhoneArchiveModule module = phoneArchiveModule();
                module.form().reloadProfiles();
                module.form().refreshDateFolders();
                contentArea.show("Archiwizacja telefonu",
                        "Przenieś nowe zdjęcia i filmy do archiwum z folderami RRRR-MM-DD, pomijając te, które już tam są.",
                        module);
            }
            case BACKUP -> {
                if (backupView == null) {
                    backupView = new BackupView(phoneArchiveProfileStore, AgentControl.createDefault());
                }
                backupView.reloadProfiles();
                contentArea.show("Kopia zapasowa",
                        "Automatyczna archiwizacja w tle według profilu „Archiwizacji telefonu”.",
                        backupView);
            }
            case PROFILES -> {
                if (profilesView == null) {
                    profilesView = new ProfilesView(List.of(new DuplicateProfileSource(duplicateProfileStore),
                            thumbnailProfileStore.asSource(), phoneArchiveProfileStore.asSource()));
                    profilesView.setOnOpen(this::openProfile);
                }
                contentArea.show("Profile",
                        "Wszystkie zapisane profile ustawień. Kliknij dwukrotnie, aby otworzyć profil w jego module.",
                        profilesView);
                profilesView.refresh();
            }
            case SETTINGS -> {
                if (settingsView == null) {
                    settingsView = new SettingsView(settings);
                }
                contentArea.show("Ustawienia",
                        "Konfiguracja aplikacji. Zmiany zaczną obowiązywać po kliknięciu „Zapisz”.",
                        settingsView);
            }
            case HELP -> {
                if (helpView == null) {
                    helpView = new HelpView();
                }
                contentArea.show("Pomoc",
                        "Instrukcja programu: spis treści po lewej, wyszukiwanie u góry (F1 otwiera ją z każdego miejsca).",
                        helpView);
            }
            default -> contentArea.showPlaceholder(section.getTitle(),
                    "Ten moduł jest w przygotowaniu.",
                    "Moduł w przygotowaniu",
                    "Funkcje sekcji „" + section.getTitle() + "” pojawią się wkrótce.");
        }
    }

    private DuplicatesModule duplicatesModule() {
        if (duplicatesModule == null) {
            duplicatesModule = new DuplicatesModule(duplicateProfileStore, settings);
        }
        return duplicatesModule;
    }

    private ThumbnailsModule thumbnailsModule() {
        if (thumbnailsModule == null) {
            thumbnailsModule = new ThumbnailsModule(thumbnailProfileStore, settings);
        }
        return thumbnailsModule;
    }

    private PhoneArchiveModule phoneArchiveModule() {
        if (phoneArchiveModule == null) {
            phoneArchiveModule = new PhoneArchiveModule(phoneArchiveProfileStore, settings);
        }
        return phoneArchiveModule;
    }

    /** Przechodzi do modułu profilu i wczytuje go. */
    private void openProfile(ProfileItem item) {
        switch (item.module()) {
            case DUPLICATES -> {
                sidebar.select(Section.DUPLICATES);
                DuplicatesModule module = duplicatesModule();
                if (module.isScanning()) {
                    return; // nie przerywamy trwającego skanowania
                }
                module.showForm();
                module.form().openProfile(item.name());
            }
            case THUMBNAILS -> {
                sidebar.select(Section.THUMBNAILS);
                ThumbnailsModule module = thumbnailsModule();
                if (module.isRunning()) {
                    return; // nie przerywamy trwającej pracy
                }
                module.showForm();
                module.form().openProfile(item.name());
            }
            case PHONE_ARCHIVE -> {
                sidebar.select(Section.PHONE_ARCHIVE);
                PhoneArchiveModule module = phoneArchiveModule();
                if (module.isRunning()) {
                    return;
                }
                module.showForm();
                module.form().openProfile(item.name());
            }
            default -> {
                // Pozostałe moduły nie mają jeszcze profili.
            }
        }
    }

    private static HBox createStatusBar() {
        Circle dot = new Circle(4);
        dot.getStyleClass().add("status-dot");

        Label status = new Label("Gotowy", dot);
        status.setGraphicTextGap(8);
        status.getStyleClass().add("status-text");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label version = new Label(PhotonApp.APP_NAME + " " + PhotonApp.APP_VERSION
                + "  •  JavaFX " + System.getProperty("javafx.version"));
        version.getStyleClass().add("status-text");

        HBox bar = new HBox(status, spacer, version);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    public Sidebar getSidebar() {
        return sidebar;
    }

    public ContentArea getContentArea() {
        return contentArea;
    }
}
