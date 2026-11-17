package com.photon.ui.window;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Operacje na oknie bez systemowej ramki: minimalizacja, maksymalizacja, zamykanie.
 * <p>
 * Maksymalizacja jest realizowana ręcznie (do obszaru roboczego ekranu), bo
 * {@code Stage.setMaximized} dla okna UNDECORATED zakrywa pasek zadań Windows.
 */
public class WindowController {

    private final Stage stage;
    private final ReadOnlyBooleanWrapper maximized = new ReadOnlyBooleanWrapper(false);
    private Rectangle2D restoreBounds;
    private BooleanSupplier closeGuard = () -> true;

    public WindowController(Stage stage) {
        this.stage = stage;
        // Alt+F4 i inne systemowe żądania zamknięcia też przechodzą przez strażnika.
        stage.setOnCloseRequest(e -> {
            if (!closeGuard.getAsBoolean()) {
                e.consume();
            }
        });
    }

    /** Warunek zamknięcia okna, np. pytanie o potwierdzenie. Zwraca false, aby anulować zamknięcie. */
    public void setCloseGuard(BooleanSupplier guard) {
        this.closeGuard = guard;
    }

    /** Położenie i rozmiar okna w stanie niezmaksymalizowanym. */
    public Rectangle2D getNormalBounds() {
        if (isMaximized() && restoreBounds != null) {
            return restoreBounds;
        }
        return new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
    }

    public Stage getStage() {
        return stage;
    }

    public void minimize() {
        stage.setIconified(true);
    }

    public void close() {
        if (closeGuard.getAsBoolean()) {
            stage.close();
        }
    }

    public void toggleMaximize() {
        if (isMaximized()) {
            restore();
        } else {
            maximize();
        }
    }

    public void maximize() {
        if (isMaximized()) {
            return;
        }
        restoreBounds = new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        applyBounds(currentScreen().getVisualBounds());
        maximized.set(true);
    }

    public void restore() {
        if (!isMaximized()) {
            return;
        }
        if (restoreBounds != null) {
            applyBounds(restoreBounds);
        }
        maximized.set(false);
    }

    public boolean isMaximized() {
        return maximized.get();
    }

    public ReadOnlyBooleanProperty maximizedProperty() {
        return maximized.getReadOnlyProperty();
    }

    /** Ekran, na którym znajduje się środek okna. */
    public Screen currentScreen() {
        List<Screen> screens = Screen.getScreensForRectangle(
                stage.getX() + stage.getWidth() / 2, stage.getY() + stage.getHeight() / 2, 1, 1);
        return screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
    }

    private void applyBounds(Rectangle2D b) {
        stage.setX(b.getMinX());
        stage.setY(b.getMinY());
        stage.setWidth(b.getWidth());
        stage.setHeight(b.getHeight());
    }
}
