package com.photon.settings;

import com.photon.remote.RemoteClient;
import com.photon.remote.RemoteProtocol;
import com.photon.remote.RemoteServer;
import com.photon.remote.RemoteServers;
import com.photon.ui.Dialogs;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignI;
import org.kordamp.ikonli.materialdesign2.MaterialDesignK;
import org.kordamp.ikonli.materialdesign2.MaterialDesignL;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.photon.ui.Forms.*;
import java.nio.file.NoSuchFileException;
import com.photon.ui.Texts;
import com.photon.ui.Background;

/**
 * Konfiguracja serwerów zdalnych (FTP, FTPS, SFTP) — jedna dla całej aplikacji. W zakładkach wybiera się
 * tylko nazwę połączenia i folder na serwerze. Połączenia zapisują się od razu (niezależnie od przycisku
 * „Zapisz” ustawień), bo korzystają z nich też profile i agent kopii zapasowej.
 */
public class RemoteServersPane extends VBox {

    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");

    private final ListView<RemoteServer> list = new ListView<>();
    private final TextField name = new TextField();
    private final ComboBox<RemoteProtocol> protocol = new ComboBox<>();
    private final TextField host = new TextField();
    private final TextField port = new TextField();
    private final TextField user = new TextField();
    private final PasswordField password = new PasswordField();
    private final CheckBox passive = new CheckBox("Tryb pasywny (zalecany — działa za routerem i zaporą)");
    private final Label hostKey = new Label();
    private final Button forgetKey = new Button("Zapomnij klucz");
    private final TextField testFolder = new TextField("/");
    private final Button test = new Button("Testuj połączenie", new FontIcon(MaterialDesignL.LAN_CONNECT));
    private final Button saveServer = new Button("Zapisz połączenie", new FontIcon(MaterialDesignC.CONTENT_SAVE_OUTLINE));
    private final Label result = new Label();
    private final VBox editor;

    /** Nazwa edytowanego połączenia przed zmianami (null = nowe). */
    private String editedName;
    private String editedHostKey;

    public RemoteServersPane() {
        super(12);
        getStyleClass().add("remote-servers");

        list.setPrefHeight(150);
        list.getStyleClass().add("folder-list");
        Label placeholder = new Label("Brak połączeń. Kliknij „Dodaj serwer”.");
        placeholder.getStyleClass().add("list-placeholder");
        list.setPlaceholder(placeholder);
        list.setCellFactory(v -> new ListCell<>() {
            private final FontIcon icon = new FontIcon(MaterialDesignS.SERVER_NETWORK);

            {
                icon.getStyleClass().add("list-icon");
            }

            @Override
            protected void updateItem(RemoteServer server, boolean empty) {
                super.updateItem(server, empty);
                if (empty || server == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(server.name() + "   —   " + server.address());
                    setGraphic(icon);
                }
            }
        });

        Button add = new Button("Dodaj serwer", new FontIcon(MaterialDesignP.PLUS));
        add.setOnAction(e -> {
            list.getSelectionModel().clearSelection();
            edit(null);
            name.requestFocus();
        });
        Button remove = iconButton(MaterialDesignD.DELETE_OUTLINE, "Usuń zaznaczone połączenie");
        remove.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> removeSelected());
        HBox toolbar = new HBox(8, add, spacer(), remove);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        protocol.getItems().setAll(RemoteProtocol.values());
        protocol.setMaxWidth(Double.MAX_VALUE);
        protocol.valueProperty().addListener((obs, old, value) -> updateProtocol());
        port.setPrefColumnCount(6);
        host.setPromptText("np. nas.local albo 192.168.1.10");
        user.setPromptText("puste = anonimowo (tylko FTP)");
        testFolder.setPromptText("/");
        name.setPromptText("np. NAS — tak połączenie będzie widoczne w zakładkach");
        forgetKey.setOnAction(e -> {
            editedHostKey = null;
            updateProtocol();
            showResult("Klucz zostanie zapomniany po zapisaniu połączenia; nowy zostanie zapamiętany "
                    + "przy następnym połączeniu.", false);
        });

        GridPane grid = formGrid();
        grid.addRow(0, fieldLabel("Nazwa połączenia"), name);
        grid.addRow(1, fieldLabel("Protokół"), protocol);
        HBox address = new HBox(8, host, new Label("port"), port);
        address.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(host, Priority.ALWAYS);
        grid.addRow(2, fieldLabel("Adres serwera"), address);
        grid.addRow(3, fieldLabel("Użytkownik"), user);
        grid.addRow(4, fieldLabel("Hasło"), password);
        HBox keyRow = new HBox(8, hostKey, forgetKey);
        keyRow.setAlignment(Pos.CENTER_LEFT);
        Label keyLabel = fieldLabel("Klucz serwera (SFTP)");
        grid.addRow(5, keyLabel, keyRow);
        grid.add(passive, 1, 6);
        HBox testRow = new HBox(8, testFolder, test);
        HBox.setHgrow(testFolder, Priority.ALWAYS);
        testRow.setAlignment(Pos.CENTER_LEFT);
        grid.addRow(7, fieldLabel("Folder do testu"), testRow);
        for (var node : List.of(keyLabel, keyRow)) {
            node.visibleProperty().bind(protocol.valueProperty().isEqualTo(RemoteProtocol.SFTP));
            node.managedProperty().bind(node.visibleProperty());
        }
        passive.visibleProperty().bind(protocol.valueProperty().isNotEqualTo(RemoteProtocol.SFTP));
        passive.managedProperty().bind(passive.visibleProperty());

        test.setOnAction(e -> testConnection());
        saveServer.getStyleClass().add("button-primary");
        saveServer.setOnAction(e -> saveEdited());
        result.getStyleClass().add("action-feedback");
        result.setWrapText(true);
        result.managedProperty().bind(result.textProperty().isNotEmpty());
        result.visibleProperty().bind(result.managedProperty());
        HBox actions = new HBox(8, spacer(), saveServer);
        actions.setAlignment(Pos.CENTER_LEFT);

        editor = new VBox(10, grid, result, actions);
        editor.getStyleClass().add("remote-editor");

        Label passwords = new Label("Hasła są zapisywane zwykłym tekstem w pliku servers.json w folderze danych "
                + "PhotoN — każdy, kto ma dostęp do tego folderu, może je odczytać. Zwykły FTP przesyła hasło "
                + "i pliki bez szyfrowania; poza siecią domową używaj FTPS albo SFTP.",
                new FontIcon(MaterialDesignI.INFORMATION_OUTLINE));
        passwords.getStyleClass().add("info-banner");
        passwords.setWrapText(true);
        passwords.setMaxWidth(Double.MAX_VALUE);

        getChildren().addAll(toolbar, list, editor, passwords);

        list.getSelectionModel().selectedItemProperty().addListener((obs, old, server) -> {
            if (server != null && !server.name().equals(editedName)) {
                edit(server);
            }
        });
        reload();
        RemoteServers.addListener(() -> Platform.runLater(this::reloadKeepingSelection));
    }

    /** Wczytuje połączenia z pliku. */
    public void reload() {
        list.getItems().setAll(RemoteServers.all());
        if (list.getItems().isEmpty()) {
            edit(null);
        } else {
            list.getSelectionModel().selectFirst();
        }
    }

    private void reloadKeepingSelection() {
        String selected = editedName;
        list.getItems().setAll(RemoteServers.all());
        list.getItems().stream().filter(s -> s.name().equals(selected)).findFirst().ifPresent(s -> {
            list.getSelectionModel().select(s);
            if (editedHostKey == null && s.hostKey() != null) {
                editedHostKey = s.hostKey(); // zapamiętany przy połączeniu
                updateProtocol();
            }
        });
    }

    private void edit(RemoteServer server) {
        RemoteServer s = server == null ? RemoteServer.empty() : server;
        editedName = server == null ? null : server.name();
        editedHostKey = s.hostKey();
        name.setText(s.name());
        protocol.setValue(s.protocol());
        host.setText(s.host());
        port.setText(s.port() > 0 ? String.valueOf(s.port()) : "");
        user.setText(s.user());
        password.setText(s.password());
        passive.setSelected(s.passive());
        testFolder.setText("/");
        result.setText("");
        updateProtocol();
    }

    private void updateProtocol() {
        RemoteProtocol p = protocol.getValue() == null ? RemoteProtocol.FTP : protocol.getValue();
        port.setPromptText(String.valueOf(p.defaultPort()));
        hostKey.setText(editedHostKey == null ? "jeszcze nieznany — zostanie zapamiętany przy pierwszym połączeniu"
                : editedHostKey);
        hostKey.setGraphic(new FontIcon(editedHostKey == null ? MaterialDesignK.KEY_OUTLINE : MaterialDesignK.KEY_CHAIN));
        forgetKey.setVisible(editedHostKey != null);
    }

    /** Połączenie z pól formularza (null i komunikat, gdy port jest niepoprawny). */
    private RemoteServer fromForm() {
        int portNumber = 0;
        String text = port.getText() == null ? "" : port.getText().trim();
        if (!text.isEmpty()) {
            try {
                portNumber = Integer.parseInt(text);
            } catch (NumberFormatException e) {
                portNumber = -1;
            }
        }
        return new RemoteServer(name.getText(), protocol.getValue(), host.getText(), portNumber, user.getText(),
                password.getText(), passive.isSelected(), editedHostKey);
    }

    private void testConnection() {
        RemoteServer server = fromForm();
        String problem = server.validate();
        if (problem != null) {
            showResult(problem, true);
            return;
        }
        String folder = testFolder.getText();
        test.setDisable(true);
        showResult("Łączenie z " + server.address() + "…", false);
        Background.supply("remote-test", () -> RemoteClient.test(server, folder), outcome -> {
            test.setDisable(false);
            if (editedHostKey == null && outcome.hostKey() != null) {
                editedHostKey = outcome.hostKey();
                updateProtocol();
            }
            showResult(outcome.message(), false);
        }, e -> {
            test.setDisable(false);
            showResult("Test nieudany: " + (e instanceof NoSuchFileException
                    ? "Połączono, ale folder " + folder + " nie istnieje na serwerze." : Texts.errorMessage(e)), true);
        });
    }

    private void saveEdited() {
        RemoteServer server = fromForm();
        List<RemoteServer> servers = new ArrayList<>(RemoteServers.all());
        servers.removeIf(s -> s.name().equals(editedName));
        int index = editedName == null ? servers.size()
                : Math.min(list.getSelectionModel().getSelectedIndex() < 0 ? servers.size()
                : list.getSelectionModel().getSelectedIndex(), servers.size());
        servers.add(index, server);
        try {
            RemoteServers.save(servers);
        } catch (IOException e) {
            showResult(e.getMessage(), true);
            return;
        }
        String renamed = editedName != null && !editedName.equals(server.name()) ? editedName : null;
        editedName = server.name();
        list.getItems().setAll(RemoteServers.all());
        list.getItems().stream().filter(s -> s.name().equals(server.name())).findFirst()
                .ifPresent(s -> list.getSelectionModel().select(s));
        showResult("Zapisano połączenie „" + server.name() + "”."
                + (renamed == null ? "" : " Profile wskazujące foldery na „" + renamed
                + "” trzeba poprawić — nazwa połączenia jest częścią ścieżki."), false);
    }

    private void removeSelected() {
        RemoteServer server = list.getSelectionModel().getSelectedItem();
        if (server == null) {
            return;
        }
        boolean confirmed = Dialogs.confirm(getScene().getWindow(), "Usunąć połączenie?",
                "Połączenie „" + server.name() + "” zostanie usunięte. Profile, które wskazują foldery na tym "
                        + "serwerze, przestaną działać, dopóki nie dodasz połączenia o tej samej nazwie.",
                "Usuń", true);
        if (!confirmed) {
            return;
        }
        List<RemoteServer> servers = new ArrayList<>(RemoteServers.all());
        servers.removeIf(s -> s.name().equals(server.name()));
        try {
            RemoteServers.save(servers);
        } catch (IOException e) {
            showResult(e.getMessage(), true);
            return;
        }
        reload();
        showResult("Usunięto połączenie „" + server.name() + "”.", false);
    }

    private void showResult(String text, boolean error) {
        result.setText(text);
        result.pseudoClassStateChanged(ERROR, error);
    }
}
