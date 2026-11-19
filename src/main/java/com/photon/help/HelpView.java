package com.photon.help;

import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Instrukcja w aplikacji (sekcja „Pomoc”, klawisz F1): spis treści, wyszukiwanie i treść
 * {@code INSTRUKCJA.md} z działającymi odnośnikami wewnątrz dokumentu.
 */
public class HelpView extends BorderPane {

    private static final PseudoClass CURRENT = PseudoClass.getPseudoClass("current");
    private static final PseudoClass MATCH = PseudoClass.getPseudoClass("match");

    private final TextField search = new TextField();
    private final Label hitCount = new Label();
    private final TreeView<HelpDocument.TocEntry> toc = new TreeView<>();
    private final ListView<HelpDocument.Hit> results = new ListView<>();
    private final VBox content = new VBox(10);
    private final ScrollPane scroll = new ScrollPane(content);
    private final List<Node> blockNodes = new ArrayList<>();
    private HelpDocument document;
    private List<HelpDocument.Hit> hits = List.of();
    private int currentHit = -1;

    public HelpView() {
        getStyleClass().add("help-view");
        content.getStyleClass().add("help-content");
        content.setPadding(new Insets(8, 28, 40, 8));
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("help-scroll");

        try {
            document = HelpDocument.load();
        } catch (IOException e) {
            setCenter(new Label("Nie udało się wczytać instrukcji: " + e.getMessage()));
            return;
        }
        HelpRenderer renderer = new HelpRenderer(this::openLink);
        for (org.commonmark.node.Node block : document.blocks()) {
            Node node = renderer.block(block);
            node.getStyleClass().add("help-block");
            blockNodes.add(node);
        }
        content.getChildren().setAll(blockNodes);

        setTop(buildSearchBar());
        StackPane left = new StackPane(buildToc(), buildResults());
        left.getStyleClass().add("help-sidebar");
        SplitPane split = new SplitPane(left, scroll);
        split.setDividerPositions(0.27);
        SplitPane.setResizableWithParent(left, false);
        setCenter(split);
    }

    // =====================================================================
    // Publiczne API (także do testów)
    // =====================================================================

    /** Wyszukuje w instrukcji i przechodzi do pierwszego trafienia. */
    public void search(String query) {
        search.setText(query);
    }

    public List<HelpDocument.Hit> hits() {
        return hits;
    }

    /** Spis treści (pozycje najwyższego poziomu). */
    public List<String> tocTitles() {
        return toc.getRoot() == null ? List.of()
                : toc.getRoot().getChildren().stream().map(i -> i.getValue().title()).toList();
    }

    /** Przewija do rozdziału o tej kotwicy (np. {@code 55-kopia-zapasowa-agent-w-tle}). */
    public boolean openSection(String id) {
        return openLink("#" + id);
    }

    public double scrollPosition() {
        return scroll.getVvalue();
    }

    // =====================================================================
    // Budowa widoku
    // =====================================================================

    private Node buildSearchBar() {
        search.setPromptText("Szukaj w instrukcji (np. autostart, serwer, duplikaty) — polskie znaki nie są wymagane");
        search.getStyleClass().add("help-search");
        search.textProperty().addListener((obs, old, text) -> runSearch(text));
        search.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                step(e.isShiftDown() ? -1 : 1);
            } else if (e.getCode() == KeyCode.ESCAPE) {
                search.clear();
            }
        });
        Button previous = new Button(null, new FontIcon(MaterialDesignC.CHEVRON_UP));
        previous.setOnAction(e -> step(-1));
        Button next = new Button(null, new FontIcon(MaterialDesignC.CHEVRON_DOWN));
        next.setOnAction(e -> step(1));
        previous.disableProperty().bind(search.textProperty().isEmpty());
        next.disableProperty().bind(search.textProperty().isEmpty());
        hitCount.getStyleClass().add("help-hit-count");
        HBox.setHgrow(search, Priority.ALWAYS);
        HBox bar = new HBox(8, new FontIcon(MaterialDesignM.MAGNIFY), search, hitCount, previous, next);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("help-search-bar");
        bar.setPadding(new Insets(0, 0, 12, 0));
        return bar;
    }

    private Node buildToc() {
        TreeItem<HelpDocument.TocEntry> root = new TreeItem<>();
        List<TreeItem<HelpDocument.TocEntry>> path = new ArrayList<>();
        path.add(root);
        for (HelpDocument.TocEntry entry : document.toc()) {
            TreeItem<HelpDocument.TocEntry> item = new TreeItem<>(entry);
            int depth = entry.level() - 1; // ## → 1, ### → 2, #### → 3
            while (path.size() > depth) {
                path.remove(path.size() - 1);
            }
            while (path.size() < depth) {
                path.add(path.get(path.size() - 1)); // brakujący poziom — pod ostatni element
            }
            path.get(path.size() - 1).getChildren().add(item);
            path.add(item);
        }
        root.getChildren().forEach(i -> i.setExpanded(true));
        toc.setRoot(root);
        toc.setShowRoot(false);
        toc.getStyleClass().add("help-toc");
        toc.setCellFactory(v -> new TreeCell<>() {
            @Override
            protected void updateItem(HelpDocument.TocEntry entry, boolean empty) {
                super.updateItem(entry, empty);
                setText(empty || entry == null ? null : entry.title());
            }
        });
        toc.getSelectionModel().selectedItemProperty().addListener((obs, old, item) -> {
            if (item != null && item.getValue() != null) {
                scrollTo(item.getValue().block(), false);
            }
        });
        toc.visibleProperty().bind(search.textProperty().isEmpty());
        return toc;
    }

    private Node buildResults() {
        results.getStyleClass().add("help-results");
        Label empty = new Label("Nic nie znaleziono.");
        empty.getStyleClass().add("list-placeholder");
        results.setPlaceholder(empty);
        results.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(HelpDocument.Hit hit, boolean isEmpty) {
                super.updateItem(hit, isEmpty);
                if (isEmpty || hit == null) {
                    setGraphic(null);
                    return;
                }
                Label section = new Label(hit.section());
                section.getStyleClass().add("help-result-section");
                Label snippet = new Label(hit.snippet());
                snippet.setWrapText(true);
                snippet.getStyleClass().add("help-result-snippet");
                VBox box = new VBox(2, section, snippet);
                box.setMaxWidth(Double.MAX_VALUE);
                snippet.maxWidthProperty().bind(results.widthProperty().subtract(30));
                setGraphic(box);
            }
        });
        results.getSelectionModel().selectedIndexProperty().addListener((obs, old, index) -> {
            if (index.intValue() >= 0 && index.intValue() != currentHit) {
                currentHit = index.intValue();
                showHit();
            }
        });
        results.visibleProperty().bind(search.textProperty().isNotEmpty());
        return results;
    }

    // =====================================================================
    // Wyszukiwanie i przewijanie
    // =====================================================================

    private void runSearch(String query) {
        blockNodes.forEach(n -> {
            n.pseudoClassStateChanged(MATCH, false);
            n.pseudoClassStateChanged(CURRENT, false);
        });
        hits = query == null || query.isBlank() ? List.of() : document.search(query);
        results.getItems().setAll(hits);
        hits.forEach(h -> blockNodes.get(h.block()).pseudoClassStateChanged(MATCH, true));
        currentHit = -1;
        if (hits.isEmpty()) {
            hitCount.setText(query == null || query.isBlank() ? "" : "brak wyników");
        } else {
            step(1);
        }
    }

    private void step(int delta) {
        if (hits.isEmpty()) {
            return;
        }
        currentHit = Math.floorMod(currentHit + delta, hits.size());
        results.getSelectionModel().select(currentHit);
        results.scrollTo(currentHit);
        showHit();
    }

    private void showHit() {
        blockNodes.forEach(n -> n.pseudoClassStateChanged(CURRENT, false));
        HelpDocument.Hit hit = hits.get(currentHit);
        hitCount.setText((currentHit + 1) + " / " + hits.size());
        scrollTo(hit.block(), true);
    }

    private boolean openLink(String target) {
        if (!target.startsWith("#")) {
            return false; // instrukcja nie zawiera odnośników zewnętrznych
        }
        int block = document.anchor(target);
        if (block < 0) {
            return false;
        }
        search.clear();
        scrollTo(block, true);
        return true;
    }

    /** Przewija tak, by blok był u góry widoku (po ułożeniu treści). */
    private void scrollTo(int block, boolean highlight) {
        Node node = blockNodes.get(block);
        if (highlight) {
            node.pseudoClassStateChanged(CURRENT, true);
        }
        Platform.runLater(() -> {
            content.applyCss();
            content.layout();
            Bounds bounds = node.getBoundsInParent();
            double contentHeight = content.getBoundsInLocal().getHeight();
            double viewport = scroll.getViewportBounds().getHeight();
            double max = Math.max(1, contentHeight - viewport);
            scroll.setVvalue(Math.min(1, Math.max(0, (bounds.getMinY() - 8) / max)));
        });
    }
}
