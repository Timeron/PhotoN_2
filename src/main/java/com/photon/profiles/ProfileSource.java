package com.photon.profiles;

import com.photon.ui.Section;

import java.io.IOException;
import java.util.List;

/**
 * Źródło profili jednego modułu. Każdy moduł z profilami dostarcza własną implementację,
 * dzięki czemu sekcja „Profile” pokazuje profile wszystkich modułów w jednym miejscu.
 */
public interface ProfileSource {

    Section module();

    List<ProfileItem> loadAll() throws IOException;

    void delete(ProfileItem item) throws IOException;
}
