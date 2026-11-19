package com.photon.help;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.ThematicBreak;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Zamienia bloki Markdown instrukcji na kontrolki JavaFX (bez przeglądarki — program pozostaje mały).
 * Wygląd nadają klasy CSS {@code help-*}.
 */
final class HelpRenderer {

    private final Consumer<String> onLink;

    /** @param onLink wywoływane po kliknięciu odnośnika (adres, np. {@code #55-kopia-zapasowa}) */
    HelpRenderer(Consumer<String> onLink) {
        this.onLink = onLink;
    }

    Node block(org.commonmark.node.Node node) {
        if (node instanceof Heading heading) {
            TextFlow flow = inline(heading);
            flow.getStyleClass().addAll("help-heading", "help-h" + heading.getLevel());
            return flow;
        }
        if (node instanceof Paragraph paragraph) {
            TextFlow flow = inline(paragraph);
            flow.getStyleClass().add("help-paragraph");
            return flow;
        }
        if (node instanceof BulletList || node instanceof OrderedList) {
            return list(node);
        }
        if (node instanceof FencedCodeBlock code) {
            return codeBlock(code.getLiteral());
        }
        if (node instanceof IndentedCodeBlock code) {
            return codeBlock(code.getLiteral());
        }
        if (node instanceof TableBlock table) {
            return table(table);
        }
        if (node instanceof BlockQuote quote) {
            VBox box = children(quote);
            box.getStyleClass().add("help-quote");
            return box;
        }
        if (node instanceof ThematicBreak) {
            Separator separator = new Separator();
            separator.getStyleClass().add("help-separator");
            return separator;
        }
        if (node instanceof HtmlBlock) {
            return new VBox(); // kotwice <a id> i komentarze — niewidoczne
        }
        return new VBox(children(node));
    }

    private VBox children(org.commonmark.node.Node parent) {
        VBox box = new VBox(6);
        for (org.commonmark.node.Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            box.getChildren().add(block(child));
        }
        return box;
    }

    // =====================================================================
    // Listy, kod, tabele
    // =====================================================================

    private Node list(org.commonmark.node.Node list) {
        VBox box = new VBox(4);
        box.getStyleClass().add("help-list");
        int number = list instanceof OrderedList ordered && ordered.getMarkerStartNumber() != null
                ? ordered.getMarkerStartNumber() : 1;
        for (org.commonmark.node.Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem)) {
                continue;
            }
            Label marker = new Label(list instanceof OrderedList ? (number++) + "." : "•");
            marker.getStyleClass().add("help-list-marker");
            marker.setMinWidth(22);
            VBox content = children(item);
            content.setSpacing(4);
            HBox row = new HBox(4, marker, content);
            HBox.setHgrow(content, Priority.ALWAYS);
            box.getChildren().add(row);
        }
        return box;
    }

    private static Node codeBlock(String code) {
        Label label = new Label(code.stripTrailing());
        label.getStyleClass().add("help-code-block");
        label.setWrapText(true);
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private Node table(TableBlock table) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("help-table");
        int row = 0;
        int columns = 0;
        List<TableRow> rows = new ArrayList<>();
        table.accept(new org.commonmark.node.AbstractVisitor() {
            @Override
            public void visit(org.commonmark.node.CustomNode node) {
                if (node instanceof TableRow tableRow) {
                    rows.add(tableRow);
                }
                visitChildren(node);
            }
        });
        for (TableRow tableRow : rows) {
            int column = 0;
            for (org.commonmark.node.Node cell = tableRow.getFirstChild(); cell != null; cell = cell.getNext()) {
                TextFlow flow = inline(cell);
                flow.getStyleClass().add(cell instanceof TableCell tc && tc.isHeader() ? "help-th" : "help-td");
                flow.setPadding(new Insets(4, 10, 4, 10));
                grid.add(flow, column++, row);
            }
            columns = Math.max(columns, column);
            row++;
        }
        // Kolumny nie mogą być węższe niż najdłuższe słowo (inaczej tekst łamie się litera po literze);
        // ostatnia — zwykle opisowa — zabiera resztę miejsca.
        for (int c = 0; c < columns; c++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setMinWidth(minColumnWidth(grid, c));
            constraints.setHgrow(c == columns - 1 ? Priority.ALWAYS : Priority.SOMETIMES);
            grid.getColumnConstraints().add(constraints);
        }
        return grid;
    }

    /** Szerokość najdłuższego słowa w kolumnie (+ marginesy), najwyżej 260 px. */
    private static double minColumnWidth(GridPane grid, int column) {
        double widest = 60;
        for (Node cell : grid.getChildren()) {
            Integer index = GridPane.getColumnIndex(cell);
            if ((index == null ? 0 : index) != column || !(cell instanceof TextFlow flow)) {
                continue;
            }
            for (Node part : flow.getChildren()) {
                if (part instanceof Text text) {
                    for (String word : text.getText().split("\s+")) {
                        Text probe = new Text(word);
                        probe.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
                        widest = Math.max(widest, probe.getLayoutBounds().getWidth() + 36);
                    }
                }
            }
        }
        return Math.min(widest, 260);
    }

    // =====================================================================
    // Tekst w akapicie
    // =====================================================================

    private TextFlow inline(org.commonmark.node.Node parent) {
        TextFlow flow = new TextFlow();
        addInline(flow, parent, List.of());
        return flow;
    }

    private void addInline(TextFlow flow, org.commonmark.node.Node parent, List<String> styles) {
        for (org.commonmark.node.Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof org.commonmark.node.Text text) {
                flow.getChildren().add(text(text.getLiteral(), styles));
            } else if (node instanceof Code code) {
                flow.getChildren().add(text(code.getLiteral(), with(styles, "help-code")));
            } else if (node instanceof StrongEmphasis) {
                addInline(flow, node, with(styles, "help-strong"));
            } else if (node instanceof Emphasis) {
                addInline(flow, node, with(styles, "help-em"));
            } else if (node instanceof Link link) {
                Hyperlink hyperlink = new Hyperlink(HelpDocument.plainText(link));
                hyperlink.getStyleClass().add("help-link");
                hyperlink.setOnAction(e -> onLink.accept(link.getDestination()));
                flow.getChildren().add(hyperlink);
            } else if (node instanceof SoftLineBreak) {
                flow.getChildren().add(text(" ", styles));
            } else if (node instanceof HardLineBreak) {
                flow.getChildren().add(text("\n", styles));
            } else {
                addInline(flow, node, styles); // np. HtmlInline (kotwica) — bez tekstu
            }
        }
    }

    private static Text text(String value, List<String> styles) {
        Text text = new Text(value);
        text.getStyleClass().add("help-text");
        text.getStyleClass().addAll(styles);
        return text;
    }

    private static List<String> with(List<String> styles, String style) {
        List<String> all = new ArrayList<>(styles);
        all.add(style);
        return all;
    }
}
