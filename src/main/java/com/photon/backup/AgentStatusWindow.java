package com.photon.backup;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.text.StyleContext;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Okienko ze stanem agenta, otwierane z ikony w zasobniku („Pokaż stan”). W odróżnieniu od powiadomień
 * Windows jest widoczne zawsze — także w trybie „Nie przeszkadzać” i przy wyłączonych powiadomieniach.
 * Pokazuje też ostatnie zdarzenia (to, co trafiłoby do powiadomień). Tworzone dopiero przy pierwszym otwarciu.
 *
 * <p>Wygląd jak w aplikacji PhotoN (ciemny motyw): własny pasek tytułu zamiast systemowego, płaskie
 * zaokrąglone przyciski i ciemne paski przewijania — agent działa bez JavaFX, więc to rysuje Swing.
 */
final class AgentStatusWindow {

    // Paleta z dark-theme.css aplikacji.
    private static final Color BG = new Color(0x0e1016);
    private static final Color SURFACE = new Color(0x151821);
    private static final Color SURFACE_2 = new Color(0x1b1f2a);
    private static final Color HOVER = new Color(0x1f2430);
    private static final Color BORDER = new Color(0x262b38);
    private static final Color BORDER_HOVER = new Color(0x353b4b);
    private static final Color TEXT = new Color(0xe6e8ef);
    private static final Color MUTED = new Color(0x8b92a5);
    private static final Color FAINT = new Color(0x5c6375);
    private static final Color ACCENT = new Color(0x7c5cff);
    private static final Color ACCENT_2 = new Color(0x5b8cff);
    private static final Color DANGER = new Color(0xe5484d);
    private static final Color DANGER_HOVER = new Color(0xef5f63);
    private static final Color DANGER_PRESSED = new Color(0xc43c40);

    private static final String FONT = "Segoe UI";
    private static final int TITLE_BAR_HEIGHT = 38;

    private enum Kind { SECONDARY, PRIMARY, DANGER }

    private final JFrame frame = new JFrame("PhotoN — kopia zapasowa");
    private final JLabel state = new JLabel();
    private final JTextArea details = area();
    private final JTextArea events = area();
    private final JButton stop = new FlatButton("Zatrzymaj kopię zapasową", Kind.DANGER);

    /**
     * @param openApp akcja „Otwórz PhotoN” albo null, gdy nie wiadomo, gdzie jest program
     */
    AgentStatusWindow(Image icon, Runnable checkNow, Runnable openApp, Runnable stopAgent) {
        UIManager.put("ToolTip.background", SURFACE_2);
        UIManager.put("ToolTip.foreground", TEXT);
        UIManager.put("ToolTip.border", BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER), BorderFactory.createEmptyBorder(4, 8, 4, 8)));
        frame.setUndecorated(true);
        frame.setIconImage(icon);
        frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        frame.setAlwaysOnTop(true);

        state.setForeground(TEXT);
        state.setFont(font(Font.BOLD, 16));

        JButton check = new FlatButton("Sprawdź teraz", Kind.PRIMARY);
        check.addActionListener(e -> checkNow.run());
        JButton open = new FlatButton("Otwórz PhotoN", Kind.SECONDARY);
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

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(check);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(open);
        buttons.add(Box.createHorizontalGlue());
        buttons.add(stop);

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.add(section("Stan", card(details)));
        center.add(Box.createVerticalStrut(12));
        JScrollPane eventsScroll = new JScrollPane(events);
        eventsScroll.setBorder(BorderFactory.createEmptyBorder());
        eventsScroll.setOpaque(false);
        eventsScroll.getViewport().setOpaque(false);
        eventsScroll.setVerticalScrollBar(darkScrollBar());
        eventsScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        eventsScroll.setPreferredSize(new Dimension(460, 110));
        center.add(section("Ostatnie zdarzenia", card(eventsScroll)));

        JPanel content = new JPanel(new BorderLayout(0, 14));
        content.setBackground(BG);
        content.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        content.add(state, BorderLayout.NORTH);
        content.add(center, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.setBorder(BorderFactory.createLineBorder(BORDER));
        root.add(titleBar(icon), BorderLayout.NORTH);
        root.add(content, BorderLayout.CENTER);
        frame.setContentPane(root);

        // Esc zamyka (chowa) okno.
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "hide");
        root.getActionMap().put("hide", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                frame.setVisible(false);
            }
        });

        frame.setSize(580, 430);
        frame.setMinimumSize(new Dimension(500, 360));
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

    // =====================================================================
    // Pasek tytułu (ciemny, przeciągany myszą)
    // =====================================================================

    private JPanel titleBar(Image icon) {
        JPanel bar = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(SURFACE);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(BORDER);
                g.fillRect(0, getHeight() - 1, getWidth(), 1);
            }
        };
        bar.setPreferredSize(new Dimension(0, TITLE_BAR_HEIGHT));

        JLabel title = new JLabel(frame.getTitle());
        title.setForeground(MUTED);
        title.setFont(font(Font.PLAIN, 12));
        title.setIconTextGap(8);
        title.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
        if (icon != null) {
            title.setIcon(new ImageIcon(icon.getScaledInstance(16, 16, Image.SCALE_SMOOTH)));
        }
        bar.add(title, BorderLayout.CENTER);

        JButton close = new WindowCloseButton();
        close.addActionListener(e -> frame.setVisible(false));
        bar.add(close, BorderLayout.EAST);

        MouseAdapter drag = new MouseAdapter() {
            private Point grab;

            @Override
            public void mousePressed(MouseEvent e) {
                grab = e.getPoint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (grab != null) {
                    Point screen = e.getLocationOnScreen();
                    frame.setLocation(screen.x - grab.x, screen.y - grab.y);
                }
            }
        };
        bar.addMouseListener(drag);
        bar.addMouseMotionListener(drag);
        title.addMouseListener(drag);
        title.addMouseMotionListener(drag);
        return bar;
    }

    /** Przycisk „✕” jak w oknie aplikacji: przezroczysty, czerwony po najechaniu. */
    private static final class WindowCloseButton extends JButton {
        WindowCloseButton() {
            setToolTipText("Zamknij (Esc)");
            setPreferredSize(new Dimension(46, TITLE_BAR_HEIGHT - 1));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setFocusable(false);
            setOpaque(false);
            setRolloverEnabled(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean hover = getModel().isRollover();
            if (getModel().isPressed()) {
                g2.setColor(DANGER_PRESSED);
                g2.fillRect(0, 0, getWidth(), getHeight());
            } else if (hover) {
                g2.setColor(DANGER);
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
            g2.setColor(hover ? Color.WHITE : MUTED);
            g2.setStroke(new BasicStroke(1.3f));
            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            int r = 5;
            g2.drawLine(cx - r, cy - r, cx + r, cy + r);
            g2.drawLine(cx - r, cy + r, cx + r, cy - r);
            g2.dispose();
        }
    }

    // =====================================================================
    // Przyciski i karty
    // =====================================================================

    /** Płaski, zaokrąglony przycisk w trzech odmianach jak w aplikacji (zwykły, główny, niebezpieczny). */
    private static final class FlatButton extends JButton {
        private final Kind kind;

        FlatButton(String text, Kind kind) {
            super(text);
            this.kind = kind;
            setFont(font(kind == Kind.SECONDARY ? Font.PLAIN : Font.BOLD, 13));
            setForeground(kind == Kind.SECONDARY ? TEXT : Color.WHITE);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 16));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            boolean enabled = isEnabled();
            boolean hover = enabled && getModel().isRollover();
            boolean pressed = enabled && getModel().isPressed();
            if (!enabled) {
                g2.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.4f));
            }
            int w = getWidth();
            int h = getHeight();
            int arc = 16;
            switch (kind) {
                case PRIMARY -> {
                    Color from = pressed ? ACCENT.darker() : hover ? ACCENT.brighter() : ACCENT;
                    Color to = pressed ? ACCENT_2.darker() : hover ? ACCENT_2.brighter() : ACCENT_2;
                    g2.setPaint(new GradientPaint(0, 0, from, w, 0, to));
                    g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
                }
                case DANGER -> {
                    g2.setColor(pressed ? DANGER_PRESSED : hover ? DANGER_HOVER : DANGER);
                    g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
                }
                case SECONDARY -> {
                    g2.setColor(pressed ? BORDER : hover ? HOVER : SURFACE_2);
                    g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
                    g2.setColor(hover ? BORDER_HOVER : BORDER);
                    g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
                }
            }
            g2.setFont(getFont());
            g2.setColor(getForeground());
            var metrics = g2.getFontMetrics();
            int x = (w - metrics.stringWidth(getText())) / 2;
            int y = (h - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(getText(), x, y);
            g2.dispose();
        }
    }

    /** Zaokrąglona karta pod treść (jak panele w aplikacji). */
    private static JPanel card(Component content) {
        JPanel card = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(SURFACE);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
                g2.setColor(BORDER);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
                g2.dispose();
            }
        };
        card.setOpaque(false);
        card.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        card.add(content, BorderLayout.CENTER);
        return card;
    }

    private static JScrollBar darkScrollBar() {
        JScrollBar bar = new JScrollBar(JScrollBar.VERTICAL);
        bar.setOpaque(false);
        bar.setPreferredSize(new Dimension(10, 0));
        bar.setUnitIncrement(16);
        bar.setUI(new BasicScrollBarUI() {
            @Override
            protected void paintTrack(Graphics g, JComponent c, Rectangle bounds) {
                // przezroczysty tor
            }

            @Override
            protected void paintThumb(Graphics g, JComponent c, Rectangle thumb) {
                if (thumb.isEmpty()) {
                    return;
                }
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(isThumbRollover() ? FAINT : BORDER_HOVER);
                g2.fillRoundRect(thumb.x + 2, thumb.y + 2, thumb.width - 4, thumb.height - 4, 6, 6);
                g2.dispose();
            }

            @Override
            protected JButton createDecreaseButton(int orientation) {
                return zeroButton();
            }

            @Override
            protected JButton createIncreaseButton(int orientation) {
                return zeroButton();
            }

            private JButton zeroButton() {
                JButton b = new JButton();
                b.setPreferredSize(new Dimension(0, 0));
                b.setMinimumSize(new Dimension(0, 0));
                b.setMaximumSize(new Dimension(0, 0));
                return b;
            }
        });
        return bar;
    }

    /** Czcionka z zapasowymi glifami (np. ⚠ z Segoe UI Symbol) — zwykłe {@code new Font} ich nie ma. */
    private static Font font(int style, int size) {
        return StyleContext.getDefaultStyleContext().getFont(FONT, style, size);
    }

    private static JTextArea area() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setForeground(TEXT);
        area.setCaretColor(TEXT);
        area.setSelectionColor(new Color(0x7c5cff).darker());
        area.setSelectedTextColor(Color.WHITE);
        area.setFont(font(Font.PLAIN, 13));
        area.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        return area;
    }

    private static JPanel section(String title, Component content) {
        JLabel label = new JLabel(title.toUpperCase());
        label.setForeground(MUTED);
        label.setFont(font(Font.BOLD, 11));
        label.setBorder(BorderFactory.createEmptyBorder(0, 2, 6, 0));
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.add(label, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }
}
