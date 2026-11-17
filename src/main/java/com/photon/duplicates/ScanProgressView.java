package com.photon.duplicates;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;
import org.kordamp.ikonli.materialdesign2.MaterialDesignS;

/**
 * Ekran postępu skanowania z możliwością przerwania.
 */
public class ScanProgressView extends StackPane {

    private final Label elapsed = new Label();
    private final Timeline clock;
    private long startMillis;

    public ScanProgressView(Task<?> task, String title, Runnable onCancel) {
        getStyleClass().add("scan-progress-view");

        FontIcon icon = new FontIcon(MaterialDesignM.MAGNIFY_SCAN);
        icon.getStyleClass().add("empty-state-icon");
        StackPane badge = new StackPane(icon);
        badge.getStyleClass().add("empty-state-badge");
        badge.setMaxSize(StackPane.USE_PREF_SIZE, StackPane.USE_PREF_SIZE);

        Label heading = new Label(title);
        heading.getStyleClass().add("empty-state-title");

        Label message = new Label();
        message.getStyleClass().add("empty-state-hint");
        message.textProperty().bind(task.messageProperty());

        ProgressBar bar = new ProgressBar();
        bar.getStyleClass().add("scan-progress");
        bar.setPrefWidth(460);
        bar.progressProperty().bind(task.progressProperty());

        elapsed.getStyleClass().add("field-hint");

        Button cancel = new Button("Przerwij", new FontIcon(MaterialDesignS.STOP));
        cancel.setOnAction(e -> {
            cancel.setDisable(true);
            message.textProperty().unbind();
            message.setText("Przerywanie…");
            onCancel.run();
        });

        VBox box = new VBox(14, badge, heading, message, bar, elapsed, cancel);
        box.setAlignment(Pos.CENTER);

        StackPane card = new StackPane(box);
        card.getStyleClass().add("data-card");
        getChildren().add(card);

        startMillis = System.currentTimeMillis();
        clock = new Timeline(new KeyFrame(Duration.seconds(1), e -> updateElapsed()));
        clock.setCycleCount(Timeline.INDEFINITE);
        clock.play();
        updateElapsed();
        task.runningProperty().addListener((obs, was, running) -> {
            if (!running) {
                clock.stop();
            }
        });
    }

    private void updateElapsed() {
        long seconds = (System.currentTimeMillis() - startMillis) / 1000;
        elapsed.setText(String.format("Czas: %d:%02d", seconds / 60, seconds % 60));
    }
}
