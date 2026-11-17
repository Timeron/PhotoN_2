package com.photon.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignI;

/**
 * Główna przestrzeń prezentacji danych: nagłówek + panel na widok modułu.
 */
public class ContentArea extends VBox {

    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final StackPane dataPane = new StackPane();

    public ContentArea() {
        getStyleClass().add("content-area");

        title.getStyleClass().add("content-title");
        subtitle.getStyleClass().add("content-subtitle");
        subtitle.setWrapText(true);
        VBox header = new VBox(4, title, subtitle);
        header.getStyleClass().add("content-header");

        dataPane.getStyleClass().add("data-pane");
        VBox.setVgrow(dataPane, Priority.ALWAYS);

        getChildren().addAll(header, dataPane);

        showPlaceholder("Witaj w PhotoN 2.0",
                "Wybierz moduł z menu po lewej stronie, aby rozpocząć pracę.",
                "Brak danych do wyświetlenia",
                "Tutaj pojawią się wyniki wybranego modułu.");
    }

    /** Pokazuje widok modułu. */
    public void show(String titleText, String subtitleText, Node content) {
        title.setText(titleText);
        subtitle.setText(subtitleText);
        dataPane.getChildren().setAll(content);
    }

    /** Pokazuje pustą kartę z komunikatem (np. dla modułów w przygotowaniu). */
    public void showPlaceholder(String titleText, String subtitleText, String heading, String hint) {
        show(titleText, subtitleText, createEmptyState(heading, hint));
    }

    private static Node createEmptyState(String headingText, String hintText) {
        FontIcon icon = new FontIcon(MaterialDesignI.IMAGE_MULTIPLE_OUTLINE);
        icon.getStyleClass().add("empty-state-icon");

        StackPane iconBadge = new StackPane(icon);
        iconBadge.getStyleClass().add("empty-state-badge");
        iconBadge.setMaxSize(StackPane.USE_PREF_SIZE, StackPane.USE_PREF_SIZE);

        Label heading = new Label(headingText);
        heading.getStyleClass().add("empty-state-title");

        Label hint = new Label(hintText);
        hint.getStyleClass().add("empty-state-hint");

        VBox box = new VBox(14, iconBadge, heading, hint);
        box.setAlignment(Pos.CENTER);

        StackPane card = new StackPane(box);
        card.getStyleClass().add("data-card");
        return card;
    }
}
