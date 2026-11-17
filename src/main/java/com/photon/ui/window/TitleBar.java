package com.photon.ui.window;

import com.photon.ui.LogoView;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignW;

/**
 * Własny, ciemny pasek tytułu: przeciąganie okna, dwuklik = maksymalizuj/przywróć,
 * przyciski minimalizuj / maksymalizuj / zamknij.
 */
public class TitleBar extends HBox {

    private final WindowController window;
    private final Stage stage;

    private double dragOffsetX;
    private double dragOffsetY;
    private boolean dragging;

    public TitleBar(WindowController window) {
        this.window = window;
        this.stage = window.getStage();
        getStyleClass().add("title-bar");
        setAlignment(Pos.CENTER_LEFT);

        // --- Obszar do przeciągania: mini logo + tytuł ---
        Label title = new Label();
        title.textProperty().bind(stage.titleProperty());
        title.getStyleClass().add("title-bar-title");

        HBox dragArea = new HBox(10, LogoView.createMark(18), title);
        dragArea.getStyleClass().add("title-bar-drag-area");
        dragArea.setAlignment(Pos.CENTER_LEFT);
        dragArea.setMaxHeight(Double.MAX_VALUE);
        HBox.setHgrow(dragArea, Priority.ALWAYS);
        installDragHandlers(dragArea);

        // --- Przyciski okna ---
        Button minimize = windowButton(MaterialDesignW.WINDOW_MINIMIZE, "Minimalizuj");
        minimize.setOnAction(e -> window.minimize());

        Button maximize = windowButton(MaterialDesignW.WINDOW_MAXIMIZE, "Maksymalizuj");
        maximize.setOnAction(e -> window.toggleMaximize());
        window.maximizedProperty().addListener((obs, was, isMax) -> {
            ((FontIcon) maximize.getGraphic()).setIconCode(
                    isMax ? MaterialDesignW.WINDOW_RESTORE : MaterialDesignW.WINDOW_MAXIMIZE);
            maximize.getTooltip().setText(isMax ? "Przywróć" : "Maksymalizuj");
        });

        Button close = windowButton(MaterialDesignW.WINDOW_CLOSE, "Zamknij");
        close.getStyleClass().add("window-button-close");
        close.setOnAction(e -> window.close());

        getChildren().addAll(dragArea, minimize, maximize, close);
    }

    private static Button windowButton(Ikon icon, String tooltip) {
        Button button = new Button(null, new FontIcon(icon));
        button.getStyleClass().add("window-button");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    private void installDragHandlers(HBox area) {
        area.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                window.toggleMaximize();
            }
        });

        area.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            dragging = true;
            dragOffsetX = e.getScreenX() - stage.getX();
            dragOffsetY = e.getScreenY() - stage.getY();
        });

        area.setOnMouseDragged(this::onDragged);

        area.setOnMouseReleased(e -> {
            if (!dragging) {
                return;
            }
            dragging = false;
            // Nie pozwól schować paska tytułu nad górną krawędzią ekranu.
            Rectangle2D vb = window.currentScreen().getVisualBounds();
            if (stage.getY() < vb.getMinY()) {
                stage.setY(vb.getMinY());
            }
        });
    }

    private void onDragged(MouseEvent e) {
        if (!dragging) {
            return;
        }
        if (window.isMaximized()) {
            // Przeciągnięcie zmaksymalizowanego okna: przywróć i zachowaj proporcję pozycji kursora.
            double ratio = e.getSceneX() / getScene().getWidth();
            window.restore();
            dragOffsetX = stage.getWidth() * ratio;
        }
        stage.setX(e.getScreenX() - dragOffsetX);
        stage.setY(e.getScreenY() - dragOffsetY);
    }
}
