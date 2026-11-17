package com.photon.profiles;

import com.photon.ui.Section;

import java.time.Instant;

/**
 * Wpis na wspólnej liście profili — niezależny od modułu, z którego pochodzi.
 *
 * @param module    moduł, do którego należy profil
 * @param name      nazwa profilu
 * @param updatedAt czas ostatniego zapisu (może być null)
 * @param summary   krótki opis ustawień profilu
 */
public record ProfileItem(Section module, String name, Instant updatedAt, String summary) {
}
