package com.photon.backup;

import java.awt.AWTException;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Ikona agenta kopii zapasowej w zasobniku systemowym (obok zegara). Pokazuje, że agent działa,
 * a w dymku — jego stan. Menu: stan, sprawdź teraz, otwórz PhotoN, zatrzymaj; dwuklik otwiera PhotoN.
 * Opcjonalnie pokazuje powiadomienia o zarchiwizowanych plikach.
 * <p>
 * Używa AWT (nie JavaFX), aby agent pozostał lekki. Gdy system nie ma zasobnika (np. w testach bez ekranu),
 * {@link #install} zwraca pusty wynik, a agent działa bez ikony.
 */
public final class AgentTray implements AgentLoop.Observer {

    /** Właściwość z pełną ścieżką programu PhotoN (przekazywana przez PhotoN przy uruchamianiu agenta). */
    public static final String APP_LAUNCHER_PROPERTY = "photon.appLauncher";

    private static final Color BRAND_START = new Color(0x7c, 0x5c, 0xff);
    private static final Color BRAND_END = new Color(0x22, 0xd3, 0xee);
    private static final Color CHECKING_START = new Color(0x22, 0xd3, 0xee);
    private static final Color CHECKING_END = new Color(0x34, 0xd3, 0x99);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    /** Windows ucina dłuższy dymek ikony. */
    private static final int MAX_TOOLTIP = 127;

    private final TrayIcon icon;
    private final MenuItem stateItem = new MenuItem("Uruchamianie…");
    private final Image idleImage;
    private final Image checkingImage;
    private final boolean notifications;
    private final AgentLoop loop;
    private final Optional<Path> appLauncher;
    private volatile AgentStatus lastStatus;
    private boolean checkingShown;
    /** Okienko stanu — tworzone przy pierwszym „Pokaż stan” (tylko w wątku AWT). */
    private AgentStatusWindow window;
    /** Ostatnie zdarzenia (najnowsze pierwsze) — widoczne w okienku, nawet gdy Windows ukrywa powiadomienia. */
    private final Deque<String> recent = new ArrayDeque<>();
    static final int MAX_RECENT = 20;

    private AgentTray(AgentLoop loop, boolean notifications, Optional<Path> appLauncher) {
        this.notifications = notifications;
        this.loop = loop;
        this.appLauncher = appLauncher;
        this.idleImage = createImage(64, BRAND_START, BRAND_END);
        this.checkingImage = createImage(64, CHECKING_START, CHECKING_END);

        PopupMenu menu = new PopupMenu();
        stateItem.setEnabled(false);
        MenuItem status = new MenuItem("Pokaż stan…");
        status.addActionListener(e -> showStatus());
        MenuItem checkNow = new MenuItem("Sprawdź teraz");
        checkNow.addActionListener(e -> loop.requestCheckNow());
        MenuItem open = new MenuItem("Otwórz PhotoN");
        appLauncher.ifPresentOrElse(path -> open.addActionListener(e -> openApp(path)), () -> open.setEnabled(false));
        MenuItem stop = new MenuItem("Zatrzymaj kopię zapasową");
        stop.addActionListener(e -> {
            stateItem.setLabel("Zatrzymywanie…");
            loop.requestStop();
        });
        menu.add(stateItem);
        menu.addSeparator();
        menu.add(status);
        menu.add(checkNow);
        menu.add(open);
        menu.addSeparator();
        menu.add(stop);

        icon = new TrayIcon(idleImage, "PhotoN — kopia zapasowa", menu);
        icon.setImageAutoSize(true);
        // Dwuklik ikony: otwórz PhotoN (a gdy nie wiadomo, gdzie jest — okienko stanu).
        icon.addActionListener(e -> appLauncher.ifPresentOrElse(AgentTray::openApp, this::showStatus));
    }

    /**
     * Dodaje ikonę do zasobnika i podłącza ją do agenta.
     *
     * @param notifications czy pokazywać powiadomienia o zarchiwizowanych plikach
     * @return ikona albo pusty wynik, gdy zasobnik systemowy jest niedostępny
     */
    public static Optional<AgentTray> install(AgentLoop loop, boolean notifications) {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            return Optional.empty();
        }
        try {
            AgentTray tray = new AgentTray(loop, notifications, appLauncher());
            SystemTray.getSystemTray().add(tray.icon);
            loop.setObserver(tray);
            return Optional.of(tray);
        } catch (AWTException | RuntimeException e) {
            System.err.println("Ikona w zasobniku systemowym niedostępna: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Usuwa ikonę z zasobnika (przy zatrzymaniu agenta). */
    public void remove() {
        try {
            EventQueue.invokeAndWait(() -> {
                if (window != null) {
                    window.dispose();
                }
                SystemTray.getSystemTray().remove(icon);
            });
        } catch (Exception e) {
            SystemTray.getSystemTray().remove(icon);
        }
    }

    @Override
    public void statusChanged(AgentStatus status) {
        lastStatus = status;
        EventQueue.invokeLater(() -> {
            icon.setToolTip(tooltip(status));
            stateItem.setLabel(stateLine(status));
            if (status.checking() != checkingShown) {
                checkingShown = status.checking();
                icon.setImage(checkingShown ? checkingImage : idleImage);
            }
            if (window != null && window.isVisible()) {
                window.update(status, recent());
            }
        });
    }

    @Override
    public void notify(String title, String message, boolean warning) {
        String line = eventLine(java.time.Instant.now(), message, warning);
        synchronized (recent) {
            recent.addFirst(line);
            while (recent.size() > MAX_RECENT) {
                recent.removeLast();
            }
        }
        EventQueue.invokeLater(() -> {
            if (window != null && window.isVisible()) {
                window.update(lastStatus, recent());
            }
            if (notifications || warning) {
                icon.displayMessage(title, message, warning ? TrayIcon.MessageType.WARNING : TrayIcon.MessageType.INFO);
            }
        });
    }

    /** Okienko stanu (zamiast powiadomienia — te Windows potrafi ukrywać, np. w trybie „Nie przeszkadzać”). */
    private void showStatus() {
        EventQueue.invokeLater(() -> {
            if (window == null) {
                window = new AgentStatusWindow(idleImage, loop::requestCheckNow,
                        appLauncher.<Runnable>map(path -> () -> openApp(path)).orElse(null), loop::requestStop);
            }
            window.show(lastStatus, recent());
        });
    }

    private List<String> recent() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }

    /** Wiersz listy ostatnich zdarzeń, np. {@code 12:48:00  Nowe duplikaty czekają na decyzję: 6.} */
    static String eventLine(java.time.Instant at, String message, boolean warning) {
        return TIME.format(at) + "  " + (warning ? "⚠ " : "") + message.replace("\n", " ");
    }

    // =====================================================================
    // Teksty
    // =====================================================================

    /** Dymek ikony (krótki — Windows ucina dłuższe). */
    static String tooltip(AgentStatus s) {
        StringBuilder text = new StringBuilder("PhotoN — kopia zapasowa: ").append(s.profile());
        if (s.checking()) {
            text.append("\nSprawdzanie…");
        } else if (s.lastCheck() != null) {
            text.append("\nSprawdzono ").append(TIME.format(s.lastCheck()));
        }
        text.append("\nZarchiwizowano: ").append(s.archived());
        if (!s.pendingDuplicates().isEmpty()) {
            text.append(", duplikaty do decyzji: ").append(s.pendingDuplicates().size());
        }
        if (s.lastProblem() != null) {
            text.append("\nProblem: ").append(s.lastProblem());
        }
        return text.length() <= MAX_TOOLTIP ? text.toString() : text.substring(0, MAX_TOOLTIP - 1) + "…";
    }

    /** Pierwsza (nieaktywna) pozycja menu. */
    static String stateLine(AgentStatus s) {
        if (s.state() == AgentStatus.State.STOPPED) {
            return "Zatrzymany";
        }
        return (s.checking() ? "Sprawdzanie…" : "Działa") + " — profil „" + s.profile() + "”";
    }

    /** Pełny stan (powiadomienie po wybraniu „Pokaż stan”). */
    static String details(AgentStatus s) {
        StringBuilder text = new StringBuilder();
        text.append("Profil: ").append(s.profile());
        text.append("\nOstatnie sprawdzenie: ").append(s.lastCheck() == null ? "—" : TIME.format(s.lastCheck()));
        text.append("\nNastępne: ").append(s.nextCheckText(TIME));
        if (s.session() != null || s.lastSession() != null) {
            text.append("\nSesja: ").append(s.sessionText(TIME));
        }
        if (!s.stalled().isEmpty()) {
            text.append("\nPrzesyłanie stoi: ").append(s.stalled().size()).append(" — wyślij ponownie: ")
                    .append(s.stalled().stream().limit(5).map(p -> String.valueOf(p.getFileName()))
                            .collect(java.util.stream.Collectors.joining(", ")));
        }
        text.append("\nZarchiwizowano: ").append(s.archived())
                .append(", miniatury: ").append(s.thumbnails())
                .append(", do śmietnika: ").append(s.movedDuplicates());
        if (!s.pendingDuplicates().isEmpty()) {
            text.append("\nDuplikaty do decyzji: ").append(s.pendingDuplicates().size()).append(" (usuń w PhotoN)");
        }
        if (s.waiting() > 0) {
            text.append("\nW trakcie przesyłania: ").append(s.waiting());
        }
        if (s.errors() > 0) {
            text.append("\nBłędy: ").append(s.errors());
        }
        if (s.lastProblem() != null) {
            text.append("\nProblem: ").append(s.lastProblem());
        }
        return text.toString();
    }

    // =====================================================================
    // PhotoN i grafika
    // =====================================================================

    /**
     * Gdzie jest program PhotoN: ścieżka przekazana przez PhotoN albo — gdy agent działa jako
     * {@code photon-backup.exe} z wersji wyeksportowanej — {@code PhotoN.exe} w tym samym folderze.
     */
    static Optional<Path> appLauncher() {
        String explicit = System.getProperty(APP_LAUNCHER_PROPERTY);
        if (explicit != null && !explicit.isBlank() && Files.isRegularFile(Path.of(explicit))) {
            return Optional.of(Path.of(explicit));
        }
        String self = System.getProperty("jpackage.app-path");
        if (self != null && !self.isBlank()) {
            Path app = Path.of(self).resolveSibling("PhotoN.exe");
            if (Files.isRegularFile(app)) {
                return Optional.of(app);
            }
        }
        return Optional.empty();
    }

    private static void openApp(Path launcher) {
        try {
            new ProcessBuilder(launcher.toString()).directory(launcher.getParent().toFile()).start();
        } catch (IOException e) {
            System.err.println("Nie udało się otworzyć PhotoN: " + e.getMessage());
        }
    }

    /** Znak logo PhotoN: zaokrąglony kwadrat z gradientem, pierścień obiektywu i „foton” w środku. */
    static BufferedImage createImage(int size, Color start, Color end) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setPaint(new GradientPaint(0, 0, start, size, size, end));
            double arc = size * 0.55;
            g.fill(new RoundRectangle2D.Double(0, 0, size, size, arc, arc));

            double c = size / 2.0;
            double lens = size * 0.25;
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke((float) Math.max(1.5, size * 0.09)));
            g.draw(new Ellipse2D.Double(c - lens, c - lens, lens * 2, lens * 2));
            double photon = size * 0.095;
            g.fill(new Ellipse2D.Double(c - photon, c - photon, photon * 2, photon * 2));
            double spark = size * 0.065;
            g.setColor(new Color(255, 255, 255, 220));
            g.fill(new Ellipse2D.Double(c + size * 0.27 - spark, c - size * 0.27 - spark, spark * 2, spark * 2));
        } finally {
            g.dispose();
        }
        return image;
    }
}
