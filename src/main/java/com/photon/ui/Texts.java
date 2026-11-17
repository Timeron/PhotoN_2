package com.photon.ui;

/**
 * Pomocnicze funkcje tekstowe dla interfejsu w języku polskim.
 */
public final class Texts {

    private Texts() {
    }

    /**
     * Polska odmiana liczebnika, np. {@code plural(5, "profil", "profile", "profili")} → „profili”.
     */
    public static String plural(int n, String one, String few, String many) {
        if (n == 1) {
            return one;
        }
        int mod10 = n % 10;
        int mod100 = n % 100;
        return (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) ? few : many;
    }

    /** Rozmiar w czytelnej postaci, np. „1,5 MB”. */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(java.util.Locale.forLanguageTag("pl-PL"), "%.1f %s", value, units[unit]);
    }

    /** Liczba wraz z odmienionym rzeczownikiem, np. „3 lokalizacje”. */
    public static String count(int n, String one, String few, String many) {
        return n + " " + plural(n, one, few, many);
    }
}
