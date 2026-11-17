package com.photon.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Karta sekcji formularza: ikona, tytuł, opis i dowolna zawartość.
 */
public class FormSection extends VBox {

    public FormSection(Ikon icon, String title, String description, Node... content) {
        getStyleClass().add("form-section");

        FontIcon fontIcon = new FontIcon(icon);
        fontIcon.getStyleClass().add("form-section-icon");
        StackPane badge = new StackPane(fontIcon);
        badge.getStyleClass().add("form-section-badge");
        badge.setMinSize(StackPane.USE_PREF_SIZE, StackPane.USE_PREF_SIZE);

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("form-section-title");
        Label descriptionLabel = new Label(description);
        descriptionLabel.getStyleClass().add("form-section-description");
        descriptionLabel.setWrapText(true);

        VBox texts = new VBox(2, titleLabel, descriptionLabel);
        HBox.setHgrow(texts, Priority.ALWAYS);
        HBox header = new HBox(14, badge, texts);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox body = new VBox(14, content);
        body.getStyleClass().add("form-section-body");

        getChildren().addAll(header, body);
    }
}
