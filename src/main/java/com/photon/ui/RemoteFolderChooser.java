package com.photon.ui;

import com.photon.remote.Locations;
import com.photon.remote.RemoteServers;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.stage.Window;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Wybór folderu na serwerze zdalnym (FTP/FTPS/SFTP z Ustawień → Serwery zdalne). Listy folderów są
 * wczytywane w tle, więc wolny serwer nie blokuje okna.
 */
public final class RemoteFolderChooser {

    private final String server;
    private final StringProperty folder = new SimpleStringProperty("/");
    private final ListView<String> list = new ListView<>();
    private final Label status = new Label();
    private final Label empty = new Label("Brak podfolderów.");

    private RemoteFolderChooser(String server, String start) {
        this.server = server;
        folder.set(start == null || start.isBlank() ? "/" : start);
    }

    /**
     * Folder na jednym z serwerów: najpierw wybór połączenia (gdy jest ich kilka), potem folderu.
     *
     * @return pełna ścieżka {@code [Nazwa]/folder} albo pusty wynik po anulowaniu
     */
    public static Optional<Path> chooseOnAnyServer(Window owner) {
        List<String> names = RemoteServers.names();
        if (names.isEmpty()) {
            Dialogs.error(owner, "Brak serwerów", "Najpierw dodaj połączenie w Ustawieniach → Serwery zdalne.");
            return Optional.empty();
        }
        Optional<String> server = names.size() == 1 ? Optional.of(names.get(0)) : chooseServer(owner, names);
        return server.flatMap(name -> choose(owner, name, "/").map(folder -> Locations.remote(name, folder)));
    }

    /**
     * Folder na wskazanym serwerze.
     *
     * @return wybrany folder, np. {@code /zdjecia/2024}
     */
    public static Optional<String> choose(Window owner, String server, String start) {
        return new RemoteFolderChooser(server, start).show(owner);
    }

    private static Optional<String> chooseServer(Window owner, List<String> names) {
        Dialog<String> dialog = Dialogs.create(owner);
        ButtonType next = new ButtonType("Dalej", ButtonBar.ButtonData.OK_DONE);
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().addAll(ButtonType.CANCEL, next);
        ComboBox<String> choice = new ComboBox<>();
        choice.getItems().setAll(names);
        choice.setValue(names.get(0));
        choice.setMaxWidth(Double.MAX_VALUE);
        pane.setContent(Dialogs.content("Serwer", "Na którym serwerze jest folder?", choice));
        pane.lookupButton(next).getStyleClass().add("button-primary");
        Dialogs.styleCancel(pane);
        dialog.setResultConverter(button -> button == next ? choice.getValue() : null);
        return dialog.showAndWait();
    }

    private Optional<String> show(Window owner) {
        Dialog<String> dialog = Dialogs.create(owner);
        dialog.getDialogPane().setPrefWidth(560);
        ButtonType choose = new ButtonType("Wybierz ten folder", ButtonBar.ButtonData.OK_DONE);
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().addAll(ButtonType.CANCEL, choose);
        pane.lookupButton(choose).getStyleClass().add("button-primary");
        Dialogs.styleCancel(pane);

        Label current = new Label();
        current.getStyleClass().add("remote-current");
        current.textProperty().bind(folder);
        Button up = new Button("W górę", new FontIcon(MaterialDesignA.ARROW_UP));
        up.disableProperty().bind(folder.isEqualTo("/"));
        up.setOnAction(e -> {
            String f = folder.get();
            int slash = f.lastIndexOf('/');
            open(slash <= 0 ? "/" : f.substring(0, slash));
        });

        list.setPrefHeight(300);
        list.getStyleClass().add("remote-folders");
        list.setPlaceholder(empty);
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                enterSelected();
            }
        });
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                enterSelected();
            }
        });
        status.getStyleClass().add("dialog-error");
        status.setWrapText(true);
        status.managedProperty().bind(status.textProperty().isNotEmpty());
        status.visibleProperty().bind(status.managedProperty());

        HBox bar = new HBox(8, up, current);
        bar.setAlignment(Pos.CENTER_LEFT);
        pane.setContent(Dialogs.content("Folder na serwerze „" + server + "”",
                "Kliknij dwukrotnie folder, aby do niego wejść. „Wybierz ten folder” wskazuje folder pokazany u góry.",
                bar, list, status));
        dialog.setResultConverter(button -> button == choose ? folder.get() : null);
        open(folder.get());
        return dialog.showAndWait();
    }

    private void enterSelected() {
        String selected = list.getSelectionModel().getSelectedItem();
        if (selected != null) {
            String f = folder.get();
            open(f.endsWith("/") ? f + selected : f + "/" + selected);
        }
    }

    /** Pokazuje folder i wczytuje w tle jego podfoldery (wynik spóźnionego wczytania innego folderu jest pomijany). */
    private void open(String dir) {
        folder.set(dir);
        list.getItems().clear();
        empty.setText("Wczytywanie…");
        status.setText("");
        Background.supply("remote-folder-list", () -> subfolders(dir), names -> {
            if (dir.equals(folder.get())) { // wynik spóźnionego wczytania innego folderu jest pomijany
                empty.setText("Brak podfolderów.");
                list.getItems().setAll(names);
            }
        }, e -> {
            if (dir.equals(folder.get())) {
                empty.setText("");
                status.setText(e instanceof NoSuchFileException
                        ? "Folder " + dir + " nie istnieje na serwerze." : Texts.errorMessage(e));
            }
        });
    }

    private List<String> subfolders(String dir) throws IOException {
        try (Stream<Path> children = Files.list(Locations.remote(server, dir))) {
            return children.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
    }
}
