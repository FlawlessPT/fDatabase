package pt.flawless.fdatabase;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MigrationsTest {

    @Test
    void parse_extractsVersions() {
        List<Migration> result = Migrations.parse(Arrays.asList(
                "db/migration/V1__init.sql", "db/migration/V2__add_col.sql", "V10__more.sql"));
        assertEquals(3, result.size());
        assertEquals(new Migration(1, "db/migration/V1__init.sql"), result.get(0));
        assertEquals(2, result.get(1).version());
        assertEquals(10, result.get(2).version());
        assertEquals("V10__more.sql", result.get(2).path());
    }

    @Test
    void parse_rejectsBadName() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Migrations.parse(Arrays.asList("db/migration/init.sql")));
        assertTrue(e.getMessage().contains("db/migration/init.sql"));
    }

    @Test
    void parse_rejectsNonIncreasing() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Migrations.parse(Arrays.asList("V2__b.sql", "V1__a.sql")));
        assertTrue(e.getMessage().contains("V1__a.sql"));
        assertTrue(e.getMessage().contains("strictly increasing"));
    }

    @Test
    void parse_rejectsDuplicates() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Migrations.parse(Arrays.asList("V1__a.sql", "other/V1__b.sql")));
        assertTrue(e.getMessage().contains("other/V1__b.sql"));
    }

    @Test
    void parse_emptyListIsEmpty() {
        assertTrue(Migrations.parse(Collections.<String>emptyList()).isEmpty());
    }
}
