package com.photon.ui;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Wspólne elementy formularzy (etykiety, podpowiedzi, siatki, spinnery).
 */
public final class Forms {

    private Forms() {
    }

    /** Dwukolumnowa siatka: etykieta (200 px) | pole (reszta szerokości). */
    public static GridPane formGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(12);
        ColumnConstraints labels = new ColumnConstraints(200);
        labels.setHalignment(HPos.LEFT);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setFillWidth(true);
        grid.getColumnConstraints().addAll(labels, fields);
        return grid;
    }

    /** Spinner z jednostką i podpowiedzią po prawej stronie. */
    public static HBox withSuffix(Spinner<Integer> spinner, String unit, String hintText) {
        HBox row = new HBox(10, spinner);
        row.setAlignment(Pos.CENTER_LEFT);
        if (!unit.isEmpty()) {
            Label unitLabel = new Label(unit);
            unitLabel.getStyleClass().add("field-unit");
            row.getChildren().add(unitLabel);
        }
        Label hint = hint(hintText);
        HBox.setMargin(hint, new Insets(0, 0, 0, 6));
        row.getChildren().add(hint);
        return row;
    }

    /** Edytowalny spinner, który zatwierdza wpisany tekst po utracie fokusu. */
    public static void setupSpinner(Spinner<Integer> spinner) {
        spinner.setEditable(true);
        spinner.setPrefWidth(130);
        spinner.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                commitSpinner(spinner);
            }
        });
    }

    /** Zatwierdza wpisany ręcznie tekst; niepoprawną wartość zastępuje poprzednią. */
    public static void commitSpinner(Spinner<Integer> spinner) {
        if (!spinner.isEditable()) {
            return;
        }
        try {
            spinner.getValueFactory().setValue(
                    spinner.getValueFactory().getConverter().fromString(spinner.getEditor().getText()));
        } catch (RuntimeException ex) {
            spinner.getEditor().setText(spinner.getValueFactory().getConverter().toString(spinner.getValue()));
        }
    }

    /** Pole tylko do odczytu, np. na ścieżkę wybieraną przyciskiem. */
    public static TextField readOnlyField() {
        TextField field = new TextField();
        field.setEditable(false);
        field.getStyleClass().add("read-only");
        field.setFocusTraversable(false);
        return field;
    }

    public static Label fieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }

    public static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-hint");
        label.setWrapText(true);
        return label;
    }

    public static Button iconButton(Ikon icon, String tooltip) {
        Button button = new Button(null, new FontIcon(icon));
        button.getStyleClass().add("button-icon");
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    public static Button linkButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("button-link");
        return button;
    }

    public static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
