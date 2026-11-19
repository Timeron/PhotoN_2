package com.photon.ui;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Proste okna dialogowe w ciemnym motywie aplikacji (bez systemowej ramki).
 */
public final class Dialogs {

    private Dialogs() {
    }

    /**
     * Pyta o tekst.
     *
     * @param validator zwraca komunikat błędu albo {@code null}, gdy wartość jest poprawna
     */
    public static Optional<String> askText(Window owner, String title, String message, String confirmText,
                                           String initialValue, Function<String, String> validator) {
        Dialog<String> dialog = create(owner);
        ButtonType confirm = new ButtonType(confirmText, ButtonBar.ButtonData.OK_DONE);
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().addAll(ButtonType.CANCEL, confirm);

        TextField field = new TextField(initialValue == null ? "" : initialValue);
        Label error = new Label();
        error.getStyleClass().add("dialog-error");
        error.managedProperty().bind(error.textProperty().isNotEmpty());
        error.visibleProperty().bind(error.managedProperty());

        pane.setContent(content(title, message, field, error));

        Button confirmButton = (Button) pane.lookupButton(confirm);
        confirmButton.getStyleClass().add("button-primary");
        styleCancel(pane);

        Runnable validate = () -> {
            String problem = validator.apply(field.getText());
            // Pusty tekst blokuje przycisk, ale nie krzyczy błędem od razu po otwarciu.
            error.setText(field.getText().isBlank() ? "" : (problem == null ? "" : problem));
            confirmButton.setDisable(problem != null);
        };
        field.textProperty().addListener((obs, old, text) -> validate.run());
        validate.run();

        dialog.setResultConverter(button -> button == confirm ? field.getText().trim() : null);
        Platform.runLater(() -> {
            field.requestFocus();
            field.selectAll();
        });
        return dialog.showAndWait();
    }

    /** Pyta o potwierdzenie. {@code destructive} wyróżnia przycisk na czerwono. */
    public static boolean confirm(Window owner, String title, String message, String confirmText,
                                  boolean destructive) {
        Dialog<Boolean> dialog = create(owner);
        ButtonType confirm = new ButtonType(confirmText, ButtonBar.ButtonData.OK_DONE);
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().addAll(ButtonType.CANCEL, confirm);
        pane.setContent(content(title, message));
        pane.lookupButton(confirm).getStyleClass().add(destructive ? "button-danger" : "button-primary");
        styleCancel(pane);
        dialog.setResultConverter(button -> button == confirm);
        return dialog.showAndWait().orElse(false);
    }

    /** Pyta o potwierdzenie, pokazując przewijaną listę szczegółów (np. ścieżek plików). */
    public static boolean confirmWithDetails(Window owner, String title, String message, List<String> details,
                                             String confirmText, boolean destructive) {
        Dialog<Boolean> dialog = create(owner);
        dialog.getDialogPane().setPrefWidth(640);
        ButtonType confirm = new ButtonType(confirmText, ButtonBar.ButtonData.OK_DONE);
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().addAll(ButtonType.CANCEL, confirm);
        TextArea list = new TextArea(String.join(System.lineSeparator(), details));
        list.setEditable(false);
        list.setWrapText(false);
        list.setPrefRowCount(Math.min(12, Math.max(3, details.size())));
        list.getStyleClass().add("dialog-details");
        pane.setContent(content(title, message, list));
        pane.lookupButton(confirm).getStyleClass().add(destructive ? "button-danger" : "button-primary");
        styleCancel(pane);
        dialog.setResultConverter(button -> button == confirm);
        return dialog.showAndWait().orElse(false);
    }

    /**
     * Folder na jednym z serwerów zdalnych: najpierw wybór połączenia (gdy jest ich kilka), potem folderu.
     *
     * @return pełna ścieżka {@code [Nazwa]/folder} albo pusty wynik po anulowaniu
     */
    public static Optional<java.nio.file.Path> chooseServerFolder(Window owner) {
        List<String> names = com.photon.remote.RemoteServers.names();
        if (names.isEmpty()) {
            error(owner, "Brak serwerów", "Najpierw dodaj połączenie w Ustawieniach → Serwery zdalne.");
            return Optional.empty();
        }
        String server = names.get(0);
        if (names.size() > 1) {
            Dialog<String> dialog = create(owner);
            ButtonType next = new ButtonType("Dalej", ButtonBar.ButtonData.OK_DONE);
            DialogPane pane = dialog.getDialogPane();
            pane.getButtonTypes().addAll(ButtonType.CANCEL, next);
            javafx.scene.control.ComboBox<String> choice = new javafx.scene.control.ComboBox<>();
            choice.getItems().setAll(names);
            choice.setValue(server);
            choice.setMaxWidth(Double.MAX_VALUE);
            pane.setContent(content("Serwer", "Na którym serwerze jest folder?", choice));
            pane.lookupButton(next).getStyleClass().add("button-primary");
            styleCancel(pane);
            dialog.setResultConverter(button -> button == next ? choice.getValue() : null);
            Optional<String> chosen = dialog.showAndWait();
            if (chosen.isEmpty()) {
                return Optional.empty();
            }
            server = chosen.get();
        }
        String name = server;
        return chooseRemoteFolder(owner, name, "/").map(folder -> com.photon.remote.Locations.remote(name, folder));
    }

    /**
     * Wybór folderu na serwerze zdalnym (listy folderów są wczytywane w tle).
     *
     * @return wybrany folder, np. {@code /zdjecia/2024}
     */
    public static Optional<String> chooseRemoteFolder(Window owner, String server, String start) {
        Dialog<String> dialog = create(owner);
        dialog.getDialogPane().setPrefWidth(560);
        ButtonType choose = new ButtonType("Wybierz ten folder", ButtonBar.ButtonData.OK_DONE);
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().addAll(ButtonType.CANCEL, choose);
        pane.lookupButton(choose).getStyleClass().add("button-primary");
        styleCancel(pane);

        javafx.beans.property.StringProperty folder = new javafx.beans.property.SimpleStringProperty(
                start == null || start.isBlank() ? "/" : start);
        Label current = new Label();
        current.getStyleClass().add("remote-current");
        current.textProperty().bind(folder);
        Button up = new Button("W górę", new org.kordamp.ikonli.javafx.FontIcon(
                org.kordamp.ikonli.materialdesign2.MaterialDesignA.ARROW_UP));
        javafx.scene.control.ListView<String> list = new javafx.scene.control.ListView<>();
        list.setPrefHeight(300);
        list.getStyleClass().add("remote-folders");
        Label status = new Label();
        status.getStyleClass().add("dialog-error");
        status.setWrapText(true);
        status.managedProperty().bind(status.textProperty().isNotEmpty());
        status.visibleProperty().bind(status.managedProperty());
        Label empty = new Label("Brak podfolderów.");
        list.setPlaceholder(empty);

        Runnable load = () -> {
            String dir = folder.get();
            list.getItems().clear();
            empty.setText("Wczytywanie…");
            status.setText("");
            Thread thread = new Thread(() -> {
                try (java.util.stream.Stream<java.nio.file.Path> children =
                             java.nio.file.Files.list(com.photon.remote.Locations.remote(server, dir))) {
                    List<String> names = children.filter(java.nio.file.Files::isDirectory)
                            .map(p -> p.getFileName().toString())
                            .sorted(String.CASE_INSENSITIVE_ORDER).toList();
                    Platform.runLater(() -> {
                        if (dir.equals(folder.get())) {
                            empty.setText("Brak podfolderów.");
                            list.getItems().setAll(names);
                        }
                    });
                } catch (java.io.IOException | RuntimeException e) {
                    String text = e instanceof java.nio.file.NoSuchFileException
                            ? "Folder " + dir + " nie istnieje na serwerze."
                            : (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    Platform.runLater(() -> {
                        if (dir.equals(folder.get())) {
                            empty.setText("");
                            status.setText(text);
                        }
                    });
                }
            }, "remote-folder-list");
            thread.setDaemon(true);
            thread.start();
        };
        list.setOnMouseClicked(e -> {
            String selected = list.getSelectionModel().getSelectedItem();
            if (e.getClickCount() == 2 && selected != null) {
                folder.set(folder.get().endsWith("/") ? folder.get() + selected : folder.get() + "/" + selected);
                load.run();
            }
        });
        list.setOnKeyPressed(e -> {
            String selected = list.getSelectionModel().getSelectedItem();
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER && selected != null) {
                folder.set(folder.get().endsWith("/") ? folder.get() + selected : folder.get() + "/" + selected);
                load.run();
            }
        });
        up.setOnAction(e -> {
            String f = folder.get();
            int slash = f.lastIndexOf('/');
            folder.set(slash <= 0 ? "/" : f.substring(0, slash));
            load.run();
        });
        up.disableProperty().bind(folder.isEqualTo("/"));

        javafx.scene.layout.HBox bar = new javafx.scene.layout.HBox(8, up, current);
        bar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        pane.setContent(content("Folder na serwerze „" + server + "”",
                "Kliknij dwukrotnie folder, aby do niego wejść. „Wybierz ten folder” wskazuje folder pokazany u góry.",
                bar, list, status));
        dialog.setResultConverter(button -> button == choose ? folder.get() : null);
        load.run();
        return dialog.showAndWait();
    }

    public static void error(Window owner, String title, String message) {
        Dialog<Void> dialog = create(owner);
        DialogPane pane = dialog.getDialogPane();
        pane.getButtonTypes().add(ButtonType.OK);
        pane.setContent(content(title, message));
        pane.lookupButton(ButtonType.OK).getStyleClass().add("button-primary");
        dialog.showAndWait();
    }

    private static <T> Dialog<T> create(Window owner) {
        Dialog<T> dialog = new Dialog<>();
        dialog.initStyle(StageStyle.UNDECORATED);
        if (owner != null) {
            dialog.initOwner(owner);
            if (owner.getScene() != null) {
                dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
            }
        }
        dialog.getDialogPane().getStyleClass().add("photon-dialog");
        dialog.getDialogPane().setPrefWidth(440);
        return dialog;
    }

    private static VBox content(String title, String message, javafx.scene.Node... extra) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("dialog-title");
        Label messageLabel = new Label(message);
        messageLabel.getStyleClass().add("dialog-message");
        messageLabel.setWrapText(true);
        // Bez tego zawinięty tekst bywa ucinany wielokropkiem zamiast zwiększać wysokość okna.
        messageLabel.setMinHeight(Region.USE_PREF_SIZE);

        VBox box = new VBox(10, titleLabel, messageLabel);
        box.getChildren().addAll(extra);
        return box;
    }

    private static void styleCancel(DialogPane pane) {
        Button cancel = (Button) pane.lookupButton(ButtonType.CANCEL);
        if (cancel != null) {
            cancel.setText("Anuluj");
        }
    }
}
