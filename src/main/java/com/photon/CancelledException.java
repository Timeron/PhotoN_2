package com.photon;

/** Długa operacja (skanowanie, archiwizacja, miniatury) została przerwana przez użytkownika. */
public class CancelledException extends RuntimeException {

    public CancelledException() {
        super("Operacja została przerwana");
    }
}
