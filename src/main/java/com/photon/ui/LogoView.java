package com.photon.ui;

import com.photon.PhotonApp;
import javafx.geometry.Pos;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

/**
 * Logo "PhotoN 2.0": znak graficzny (obiektyw z fotonem) + logotyp.
 */
public class LogoView extends HBox {

    public static final Color BRAND_START = Color.web("#7c5cff");
    public static final Color BRAND_END = Color.web("#22d3ee");

    public LogoView() {
        getStyleClass().add("logo");
        setSpacing(12);
        setAlignment(Pos.CENTER_LEFT);

        Text photo = new Text("Photo");
        photo.getStyleClass().add("logo-text");
        Text n = new Text("N");
        n.getStyleClass().addAll("logo-text", "logo-text-accent");
        TextFlow name = new TextFlow(photo, n);

        Label version = new Label(PhotonApp.APP_VERSION);
        version.getStyleClass().add("logo-version");

        HBox nameRow = new HBox(8, name, version);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        Label tagline = new Label("Menedżer zdjęć");
        tagline.getStyleClass().add("logo-tagline");

        getChildren().addAll(createMark(42), new VBox(0, nameRow, tagline));
    }

    /**
     * Znak graficzny logo: zaokrąglony kwadrat z gradientem, pierścień obiektywu
     * i świecący "foton" w centrum.
     */
    public static StackPane createMark(double size) {
        LinearGradient gradient = new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, BRAND_START), new Stop(1, BRAND_END));

        Rectangle background = new Rectangle(size, size, gradient);
        background.setArcWidth(size * 0.55);
        background.setArcHeight(size * 0.55);

        Circle lens = new Circle(size * 0.25);
        lens.setFill(Color.TRANSPARENT);
        lens.setStroke(Color.WHITE);
        lens.setStrokeWidth(Math.max(1.5, size * 0.075));

        Circle photon = new Circle(size * 0.085, Color.WHITE);

        Circle spark = new Circle(size * 0.055, Color.color(1, 1, 1, 0.85));
        spark.setTranslateX(size * 0.27);
        spark.setTranslateY(-size * 0.27);

        StackPane mark = new StackPane(background, lens, photon, spark);
        mark.getStyleClass().add("logo-mark");
        mark.setMinSize(size, size);
        mark.setMaxSize(size, size);
        return mark;
    }

    /** Renderuje znak logo do obrazka (ikona okna / paska zadań). */
    public static Image createIconImage(double size) {
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        return createMark(size).snapshot(params, null);
    }
}
