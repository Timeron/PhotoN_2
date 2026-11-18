package com.photon.phonearchive;

/**
 * Co zrobić z plikami z folderu archiwizowanego, które już są w archiwum.
 * W zakładce „Archiwizacja telefonu” na końcu pojawia się pytanie z listą plików;
 * w archiwizacji w tle przenoszenie jest automatyczne, a usuwanie czeka na potwierdzenie.
 */
public enum PhoneDuplicateAction {

    /** Domyślnie: przeniesienie do folderu zastępczego („śmietnika”). */
    MOVE_TO_FOLDER("Przenieś do folderu zastępczego (śmietnika)"),
    REMOVE("Usuń z folderu archiwizowanego (po potwierdzeniu)");

    private final String label;

    PhoneDuplicateAction(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
