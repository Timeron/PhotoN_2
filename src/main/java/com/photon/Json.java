package com.photon;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import com.photon.remote.Locations;

/**
 * Wspólna konfiguracja JSON aplikacji (profile, ustawienia, stan okna).
 */
public final class Json {

    private static final ObjectMapper MAPPER = createMapper();

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** Zapisuje obiekt do pliku atomowo (przez plik tymczasowy), tworząc brakujące katalogi. */
    public static void writeAtomically(Path target, Object value) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path temp = Files.createTempFile(dir, "photon-", ".tmp");
        try {
            MAPPER.writeValue(temp.toFile(), value);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static ObjectMapper createMapper() {
        SimpleModule paths = new SimpleModule("paths");
        // Ścieżki zapisujemy jako zwykły tekst (czytelny i edytowalny ręcznie), a nie jako URI.
        // Foldery na serwerach zdalnych: [Nazwa połączenia]/folder (patrz Locations).
        paths.addSerializer(Path.class, ToStringSerializer.instance);
        paths.addDeserializer(Path.class, new StdScalarDeserializer<>(Path.class) {
            @Override
            public Path deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                String value = p.getValueAsString();
                return value == null || value.isBlank() ? null : Locations.parse(value);
            }
        });

        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(paths)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }
}
