package com.photon.duplicates;

import com.photon.AppPaths;
import com.photon.profiles.ProfileStore;

import java.nio.file.Path;

/**
 * Profile wyszukiwania duplikatów (pliki JSON w {@code profiles/duplicates}).
 */
public class DuplicateProfileStore extends ProfileStore<DuplicateSearchOptions> {

    public DuplicateProfileStore(Path directory) {
        super(directory, DuplicateSearchOptions.class);
    }

    public static DuplicateProfileStore createDefault() {
        return new DuplicateProfileStore(AppPaths.profilesDir("duplicates"));
    }
}
