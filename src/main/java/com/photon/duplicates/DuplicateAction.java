package com.photon.duplicates;

/**
 * Co zrobić ze znalezionymi duplikatami (poza oryginałem).
 */
public enum DuplicateAction {

    REPORT_ONLY("Tylko pokaż wyniki — zdecyduję ręcznie"),
    MOVE_TO_TRASH("Przenieś duplikaty do kosza"),
    MOVE_TO_FOLDER("Przenieś duplikaty do wskazanego folderu");

    private final String label;

    DuplicateAction(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
