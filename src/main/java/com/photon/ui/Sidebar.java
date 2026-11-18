package com.photon.ui;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * Menu boczne z logo i przyciskami sekcji.
 * Wybraną sekcję udostępnia przez {@link #selectedSectionProperty()}.
 */
public class Sidebar extends VBox {

    private final ToggleGroup group = new ToggleGroup();
    private final ReadOnlyObjectWrapper<Section> selectedSection = new ReadOnlyObjectWrapper<>();

    public Sidebar() {
        getStyleClass().add("sidebar");
        group.selectedToggleProperty().addListener((obs, old, toggle) ->
                selectedSection.set(toggle == null ? null : (Section) toggle.getUserData()));

        VBox tools = new VBox(4,
                navButton(Section.DUPLICATES),
                navButton(Section.ARCHIVE),
                navButton(Section.PHONE_ARCHIVE),
                navButton(Section.THUMBNAILS),
                navButton(Section.BACKUP));

        VBox account = new VBox(4,
                navButton(Section.PROFILES),
                navButton(Section.SETTINGS));

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(
                new LogoView(),
                sectionLabel("NARZĘDZIA"),
                tools,
                spacer,
                new Separator(),
                account);
    }

    private static Label sectionLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("sidebar-section-label");
        return label;
    }

    private ToggleButton navButton(Section section) {
        FontIcon icon = new FontIcon(section.getIcon());

        ToggleButton button = new ToggleButton(section.getTitle(), icon);
        button.getStyleClass().add("nav-button");
        button.setUserData(section);
        button.setToggleGroup(group);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setGraphicTextGap(14);
        button.setMaxWidth(Double.MAX_VALUE);

        // Kliknięcie w już wybraną pozycję nie odznacza jej.
        button.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (button.isSelected()) {
                e.consume();
            }
        });

        return button;
    }

    /** Programowo wybiera sekcję (tak jak kliknięcie w menu). */
    public void select(Section section) {
        group.getToggles().stream()
                .filter(t -> t.getUserData() == section)
                .findFirst()
                .ifPresent(t -> t.setSelected(true));
    }

    /** Aktualnie wybrana sekcja menu (null, gdy nic nie wybrano). */
    public ReadOnlyObjectProperty<Section> selectedSectionProperty() {
        return selectedSection.getReadOnlyProperty();
    }
}
