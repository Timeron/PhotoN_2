package com.photon.ui.window;

import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

/**
 * Zmiana rozmiaru okna bez systemowej ramki — przeciąganiem krawędzi i narożników.
 */
public final class ResizeHelper {

    private static final double BORDER = 6;

    private final WindowController window;
    private final Stage stage;
    private final Scene scene;

    private Cursor edge = Cursor.DEFAULT;
    private boolean resizing;
    private double startScreenX, startScreenY;
    private double startX, startY, startW, startH;

    private ResizeHelper(WindowController window, Scene scene) {
        this.window = window;
        this.stage = window.getStage();
        this.scene = scene;
    }

    public static void install(WindowController window, Scene scene) {
        ResizeHelper helper = new ResizeHelper(window, scene);
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, helper::onMoved);
        scene.addEventFilter(MouseEvent.MOUSE_EXITED_TARGET, e -> {
            if (!helper.resizing) {
                scene.setCursor(null);
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, helper::onPressed);
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, helper::onDragged);
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, helper::onReleased);
    }

    private void onMoved(MouseEvent e) {
        edge = window.isMaximized() ? Cursor.DEFAULT : detectEdge(e.getSceneX(), e.getSceneY());
        scene.setCursor(edge == Cursor.DEFAULT ? null : edge);
    }

    private void onPressed(MouseEvent e) {
        if (edge == Cursor.DEFAULT || window.isMaximized() || !e.isPrimaryButtonDown()) {
            return;
        }
        resizing = true;
        startScreenX = e.getScreenX();
        startScreenY = e.getScreenY();
        startX = stage.getX();
        startY = stage.getY();
        startW = stage.getWidth();
        startH = stage.getHeight();
        e.consume();
    }

    private void onDragged(MouseEvent e) {
        if (!resizing) {
            return;
        }
        double dx = e.getScreenX() - startScreenX;
        double dy = e.getScreenY() - startScreenY;
        double minW = Math.max(stage.getMinWidth(), 200);
        double minH = Math.max(stage.getMinHeight(), 150);

        if (edge == Cursor.E_RESIZE || edge == Cursor.NE_RESIZE || edge == Cursor.SE_RESIZE) {
            stage.setWidth(Math.max(minW, startW + dx));
        }
        if (edge == Cursor.S_RESIZE || edge == Cursor.SE_RESIZE || edge == Cursor.SW_RESIZE) {
            stage.setHeight(Math.max(minH, startH + dy));
        }
        if (edge == Cursor.W_RESIZE || edge == Cursor.NW_RESIZE || edge == Cursor.SW_RESIZE) {
            double w = Math.max(minW, startW - dx);
            stage.setX(startX + startW - w);
            stage.setWidth(w);
        }
        if (edge == Cursor.N_RESIZE || edge == Cursor.NW_RESIZE || edge == Cursor.NE_RESIZE) {
            double h = Math.max(minH, startH - dy);
            stage.setY(startY + startH - h);
            stage.setHeight(h);
        }
        e.consume();
    }

    private void onReleased(MouseEvent e) {
        if (resizing) {
            resizing = false;
            e.consume();
        }
    }

    private Cursor detectEdge(double x, double y) {
        double w = scene.getWidth();
        double h = scene.getHeight();
        boolean left = x < BORDER;
        boolean right = x > w - BORDER;
        boolean top = y < BORDER;
        boolean bottom = y > h - BORDER;

        if (top && left) return Cursor.NW_RESIZE;
        if (top && right) return Cursor.NE_RESIZE;
        if (bottom && left) return Cursor.SW_RESIZE;
        if (bottom && right) return Cursor.SE_RESIZE;
        if (top) return Cursor.N_RESIZE;
        if (bottom) return Cursor.S_RESIZE;
        if (left) return Cursor.W_RESIZE;
        if (right) return Cursor.E_RESIZE;
        return Cursor.DEFAULT;
    }
}
