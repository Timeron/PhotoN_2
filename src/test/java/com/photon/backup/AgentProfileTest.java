package com.photon.backup;

import com.photon.phonearchive.PhoneArchiveOptions;
import com.photon.phonearchive.PhoneDuplicateAction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Czy profile mogą działać razem w jednym agencie. */
class AgentProfileTest {

    @TempDir
    Path dir;

    @Test
    void separateFoldersAreFine() throws IOException {
        assertNull(AgentProfile.validate(List.of(profile("Telefon", "telefon", "archiwum"),
                profile("Tablet", "tablet", "archiwum"))), "wspólne archiwum jest dozwolone");
    }

    @Test
    void overlappingSourcesAreRejected() throws IOException {
        String problem = AgentProfile.validate(List.of(profile("Telefon", "upload", "a1"),
                profile("Camera", "upload/Camera", "a2")));
        assertTrue(problem.contains("pokrywające się foldery źródłowe"), problem);
    }

    @Test
    void sourceInsideOtherProfilesArchiveIsRejected() throws IOException {
        String problem = AgentProfile.validate(List.of(profile("Telefon", "telefon", "archiwum"),
                profile("Archiwum", "archiwum/2020-01-01", "inne")));
        assertTrue(problem.contains("pokrywa się z folderem, do którego zapisuje profil „Telefon”"), problem);

        PhoneArchiveOptions withTrash = new PhoneArchiveOptions(folder("a"), folder("archA"), true, false, 800, null,
                PhoneDuplicateAction.MOVE_TO_FOLDER, folder("smietnik"));
        String trash = AgentProfile.validate(List.of(new AgentProfile("A", withTrash), profile("B", "smietnik", "archB")));
        assertTrue(trash.contains("profil „A”"), trash);
    }

    @Test
    void emptyAndRepeatedSelectionsAreRejected() throws IOException {
        assertEquals("Wybierz co najmniej jeden profil.", AgentProfile.validate(List.of()));
        AgentProfile p = profile("Telefon", "telefon", "archiwum");
        assertTrue(AgentProfile.validate(List.of(p, p)).contains("więcej niż raz"));
    }

    @Test
    void commandLineAcceptsSeveralProfiles() {
        assertEquals(List.of("Telefon", "Tablet"),
                BackupAgent.profileNames(new String[]{"--profile", "Telefon", "--profile", "Tablet"}));
        assertNull(BackupAgent.profileNames(new String[]{"--profile"}));
        assertNull(BackupAgent.profileNames(new String[]{"--profile", "A", "--cos", "B"}));
        assertEquals(List.of("--profile", "A", "--profile", "B"),
                AgentControl.command(List.of("A", "B"), null).stream()
                        .dropWhile(a -> !a.equals("--profile")).toList());
    }

    private AgentProfile profile(String name, String source, String target) throws IOException {
        return new AgentProfile(name, new PhoneArchiveOptions(folder(source), dir.resolve(target), true));
    }

    private Path folder(String name) throws IOException {
        return Files.createDirectories(dir.resolve(name));
    }
}
