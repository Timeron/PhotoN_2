package com.photon.duplicates;

/**
 * Który plik z grupy duplikatów uznać za oryginał (do zachowania).
 */
public enum KeepStrategy {

    OLDEST("Najstarszy (wg daty wykonania / utworzenia)"),
    NEWEST("Najnowszy"),
    HIGHEST_QUALITY("Najwyższa rozdzielczość / największy plik"),
    LOCATION_PRIORITY("Z lokalizacji o najwyższym priorytecie (kolejność na liście)"),
    SHORTEST_PATH("Najkrótsza ścieżka");

    private final String label;

    KeepStrategy(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
