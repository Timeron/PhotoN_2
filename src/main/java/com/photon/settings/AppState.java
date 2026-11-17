package com.photon.settings;

import com.photon.ui.Section;

/**
 * Stan aplikacji zapamiętywany między uruchomieniami (nie są to ustawienia edytowane przez użytkownika).
 *
 * @param lastSection ostatnio otwarta sekcja menu
 * @param x           położenie okna (null = nie zapisano)
 * @param y           położenie okna
 * @param width       szerokość okna (w stanie niezmaksymalizowanym)
 * @param height      wysokość okna (w stanie niezmaksymalizowanym)
 * @param maximized   czy okno było zmaksymalizowane
 */
public record AppState(Section lastSection, Double x, Double y, Double width, Double height, boolean maximized) {

    public static AppState empty() {
        return new AppState(null, null, null, null, null, false);
    }

    public boolean hasWindowBounds() {
        return x != null && y != null && width != null && height != null;
    }
}
