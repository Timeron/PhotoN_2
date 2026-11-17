package com.photon.ui;

import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignB;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignI;

/**
 * Sekcje (moduły) dostępne w menu bocznym.
 */
public enum Section {

    DUPLICATES("Szukaj Duplikatów", MaterialDesignC.CONTENT_DUPLICATE),
    ARCHIVE("Archiwizacja", MaterialDesignA.ARCHIVE_OUTLINE),
    THUMBNAILS("Miniaturyzacja", MaterialDesignI.IMAGE_SIZE_SELECT_LARGE),
    BACKUP("Kopia Zapasowa", MaterialDesignB.BACKUP_RESTORE),
    PROFILES("Profile", MaterialDesignA.ACCOUNT_MULTIPLE_OUTLINE),
    SETTINGS("Ustawienia", MaterialDesignC.COG_OUTLINE);

    private final String title;
    private final Ikon icon;

    Section(String title, Ikon icon) {
        this.title = title;
        this.icon = icon;
    }

    public String getTitle() {
        return title;
    }

    public Ikon getIcon() {
        return icon;
    }
}
