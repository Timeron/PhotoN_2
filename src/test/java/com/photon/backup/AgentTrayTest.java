package com.photon.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Teksty i grafika ikony w zasobniku (sam zasobnik nie jest dostępny w testach bez ekranu). */
class AgentTrayTest {

    @TempDir
    Path dir;

    @Test
    void tooltipIsShortAndInformative() {
        AgentStatus status = status("Telefon z bardzo długą nazwą profilu, która nie zmieści się w dymku ikony", 12,
                "Folder do archiwizacji nie istnieje: D:\\Telefon\\DCIM\\Camera\\bardzo\\długa\\ścieżka");
        String tooltip = AgentTray.tooltip(status);
        assertTrue(tooltip.length() <= 127, "Windows ucina dłuższe dymki: " + tooltip.length());
        assertTrue(tooltip.startsWith("PhotoN — kopia zapasowa"));

        String shortTip = AgentTray.tooltip(status("Telefon", 3, null));
        assertTrue(shortTip.contains("Telefon"));
        assertTrue(shortTip.contains("Zarchiwizowano: 3"));
    }

    @Test
    void menuLineAndDetails() {
        AgentStatus status = status("Telefon", 5, null);
        assertEquals("Działa — profil „Telefon”", AgentTray.stateLine(status));
        String details = AgentTray.details(status);
        assertTrue(details.contains("Profil: Telefon"));
        assertTrue(details.contains("Zarchiwizowano: 5"));
        assertTrue(details.contains("co 15 sekund"), details);
        assertTrue(details.contains("Duplikaty do decyzji: 1"));
    }

    @Test
    void drawsLogoMark() {
        BufferedImage image = AgentTray.createImage(64, new Color(0x7c5cff), new Color(0x22d3ee));
        assertEquals(64, image.getWidth());
        assertEquals(0, image.getRGB(0, 0) >>> 24, "zaokrąglony róg przezroczysty");
        assertEquals(Color.WHITE.getRGB(), image.getRGB(32, 32), "biały „foton” w środku");
        assertTrue((image.getRGB(10, 32) >>> 24) == 255, "tło nieprzezroczyste");
    }

    @Test
    void findsPhotonLauncher() throws IOException {
        Path exe = Files.writeString(dir.resolve("PhotoN.exe"), "x");
        String previous = System.getProperty(AgentTray.APP_LAUNCHER_PROPERTY);
        try {
            System.setProperty(AgentTray.APP_LAUNCHER_PROPERTY, exe.toString());
            assertEquals(exe, AgentTray.appLauncher().orElseThrow());
            System.setProperty(AgentTray.APP_LAUNCHER_PROPERTY, dir.resolve("brak.exe").toString());
            assertTrue(AgentTray.appLauncher().isEmpty(), "nieistniejący program — „Otwórz PhotoN” wyłączone");
        } finally {
            if (previous == null) {
                System.clearProperty(AgentTray.APP_LAUNCHER_PROPERTY);
            } else {
                System.setProperty(AgentTray.APP_LAUNCHER_PROPERTY, previous);
            }
        }
    }

    private static AgentStatus status(String profile, int archived, String problem) {
        Instant now = Instant.now();
        return new AgentStatus(AgentStatus.State.RUNNING, 123, profile, Path.of("C:\\Telefon"), Path.of("D:\\Archiwum"),
                now, now, now, now.plusSeconds(15), 15, false, true, false, archived, 0, 0, 0, 0,
                List.of(new AgentStatus.PendingDuplicate(Path.of("C:\\Telefon\\a.jpg"), Path.of("D:\\Archiwum\\a.jpg"), 1)),
                problem, null);
    }
}
