package com.photon.backup;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Rectangle;
import java.util.List;
import java.awt.Component;
import java.awt.Dimension;

/**
 * Okienko ze stanem agenta, otwierane z ikony w zasobniku („Pokaż stan”). W odróżnieniu od powiadomień
 * Windows jest widoczne zawsze — także w trybie „Nie przeszkadzać” i przy wyłączonych powiadomieniach.
 * Pokazuje też ostatnie zdarzenia (to, co trafiłoby do powiadomień). Tworzone dopiero przy pierwszym otwarciu.
 */
final class AgentStatusWindow {

    private static final Color BACKGROUND = new Color(0x1b, 0x1d, 0x26);
    private static final Color PANEL = new Color(0x24, 0x27, 0x33);
    private static final Color TEXT = new Color(0xe6, 0xe6, 0xf0);
    private static final Color MUTED = new Color(0x9a, 0x9c, 0xb0);

    private final JFrame frame = new JFrame("PhotoN — kopia zapasowa");
    private final JLabel state = new JLabel();
    private final JTextArea details = area();
    private final JTextArea events = area();
    private final JButton stop = new JButton("Zatrzymaj kopię zapasową");

    /**
     * @param openApp akcja „Otwórz PhotoN” albo null, gdy nie wiadomo, gdzie jest program
     */
    AgentStatusWindow(Image icon, Runnable checkNow, Runnable openApp, Runnable stopAgent) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // zostaje domyślny wygląd Swing
        }
        frame.setIconImage(icon);
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        frame.setAlwaysOnTop(true);

        state.setForeground(TEXT);
        state.setFont(state.getFont().deriveFont(Font.BOLD, 15f));

        JButton check = new JButton("Sprawdź teraz");
        check.addActionListener(e -> checkNow.run());
        JButton open = new JButton("Otwórz PhotoN");
        open.setEnabled(openApp != null);
        if (openApp != null) {
            open.addActionListener(e -> openApp.run());
        } else {
            open.setToolTipText("Dostępne w wersji wyeksportowanej (PhotoN.exe)");
        }
        stop.addActionListener(e -> {
            stop.setEnabled(false);
            state.setText("Zatrzymywanie…");
            stopAgent.run();
        });
        JButton close = new JButton("Zamknij");
        close.addActionListener(e -> frame.setVisible(false));

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(check);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(open);
        buttons.add(Box.createHorizontalGlue());
        buttons.add(stop);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(close);

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(section("Stan", details));
        center.add(Box.createVerticalStrut(10));
        JScrollPane eventsScroll = new JScrollPane(events);
        eventsScroll.setBorder(BorderFactory.createEmptyBorder());
        eventsScroll.getViewport().setBackground(PANEL);
        eventsScroll.setPreferredSize(new Dimension(460, 110));
        center.add(section("Ostatnie zdarzenia", eventsScroll));

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(BACKGROUND);
        root.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        root.add(state, BorderLayout.NORTH);
        root.add(center, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        frame.setContentPane(root);
        frame.setSize(560, 400);
        frame.setMinimumSize(new Dimension(480, 320));
    }

    /** Pokazuje okno w prawym dolnym rogu ekranu (nad paskiem zadań) i wysuwa je na wierzch. */
    void show(AgentStatus status, List<String> recent) {
        update(status, recent);
        if (!frame.isVisible()) {
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            frame.setLocation(screen.x + screen.width - frame.getWidth() - 16,
                    screen.y + screen.height - frame.getHeight() - 16);
            frame.setVisible(true);
        }
        frame.setExtendedState(JFrame.NORMAL);
        frame.toFront();
        frame.requestFocus();
    }

    /** Odświeża treść (gdy okno jest otwarte). */
    void update(AgentStatus status, List<String> recent) {
        if (status == null) {
            state.setText("Agent uruchamia się…");
            details.setText("");
        } else {
            state.setText(AgentTray.stateLine(status));
            details.setText(AgentTray.details(status));
            stop.setEnabled(status.state() != AgentStatus.State.STOPPED && !state.getText().startsWith("Zatrzymywanie"));
        }
        events.setText(recent.isEmpty() ? "Brak zdarzeń od uruchomienia agenta." : String.join("\n", recent));
        events.setCaretPosition(0);
    }

    boolean isVisible() {
        return frame.isVisible();
    }

    void dispose() {
        frame.dispose();
    }

    private static JTextArea area() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBackground(PANEL);
        area.setForeground(TEXT);
        area.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        area.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        return area;
    }

    private static JPanel section(String title, Component content) {
        JLabel label = new JLabel(title.toUpperCase());
        label.setForeground(MUTED);
        label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.add(label, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }
}
