package com.photon.ui;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.StageStyle;
import javafx.stage.Window;

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
