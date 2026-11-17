package com.photon.duplicates;

import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignE;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;

/**
 * Sposób rozpoznawania duplikatów.
 */
public enum SearchMethod {

    EXACT("Identyczne pliki",
            "Porównanie sum kontrolnych (SHA-256). Znajduje wyłącznie kopie identyczne bajt w bajt — najpewniejsza metoda.",
            MaterialDesignF.FINGERPRINT),

    SIMILAR("Podobne zdjęcia",
            "Hash percepcyjny obrazu. Znajduje kopie przeskalowane, ponownie skompresowane lub zapisane w innym formacie. Dotyczy tylko zdjęć.",
            MaterialDesignE.EYE_OUTLINE),

    METADATA("Metadane EXIF",
            "Ta sama data i godzina wykonania, aparat oraz rozdzielczość. Przydatne, gdy plik był edytowany, ale pochodzi z tego samego ujęcia.",
            MaterialDesignC.CAMERA_OUTLINE),

    NAME_SIZE("Nazwa i rozmiar",
            "Porównuje tylko nazwę pliku i jego rozmiar. Najszybsza, ale najmniej dokładna — dobra do wstępnego przeglądu.",
            MaterialDesignF.FILE_DOCUMENT_OUTLINE);

    private final String title;
    private final String description;
    private final Ikon icon;

    SearchMethod(String title, String description, Ikon icon) {
        this.title = title;
        this.description = description;
        this.icon = icon;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Ikon getIcon() {
        return icon;
    }

    @Override
    public String toString() {
        return title;
    }
}
