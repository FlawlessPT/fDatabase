package pt.flawless.fdatabase;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseConfigTest {

    private static final String FULL_YAML =
            "database:\n"
            + "  host: db.example.com\n"
            + "  port: 3307\n"
            + "  name: mydb\n"
            + "  user: myuser\n"
            + "  password: 'filesecret'\n"
            + "  ssl: true\n"
            + "  pool:\n"
            + "    maximumPoolSize: 4\n"
            + "    connectionTimeoutMs: 5000\n";

    private static ConfigurationSection section(String yaml) {
        return YamlConfiguration.loadConfiguration(new StringReader(yaml)).getConfigurationSection("database");
    }

    private static Map<String, String> noEnv() {
        return Collections.emptyMap();
    }

    @Test
    void load_usesFileValues() {
        DatabaseConfig c = DatabaseConfig.load(section(FULL_YAML), noEnv());
        assertEquals("db.example.com", c.host());
        assertEquals(3307, c.port());
        assertEquals("mydb", c.name());
        assertEquals("myuser", c.user());
        assertEquals("filesecret", c.password());
        assertTrue(c.ssl());
        assertEquals(4, c.maximumPoolSize());
        assertEquals(5000, c.connectionTimeoutMs());
    }

    @Test
    void load_envOverridesFile() {
        Map<String, String> env = new HashMap<>();
        env.put("FLAWLESS_DB_HOST", "envhost");
        env.put("FLAWLESS_DB_PORT", "3310");
        env.put("FLAWLESS_DB_NAME", "envdb");
        env.put("FLAWLESS_DB_USER", "envuser");
        env.put("FLAWLESS_DB_PASSWORD", "envsecret");
        env.put("FLAWLESS_DB_SSL", "false");
        DatabaseConfig c = DatabaseConfig.load(section(FULL_YAML), env);
        assertEquals("envhost", c.host());
        assertEquals(3310, c.port());
        assertEquals("envdb", c.name());
        assertEquals("envuser", c.user());
        assertEquals("envsecret", c.password());
        assertFalse(c.ssl());
        // pool settings only come from the file
        assertEquals(4, c.maximumPoolSize());
        assertEquals(5000, c.connectionTimeoutMs());
    }

    @Test
    void load_missingPasswordFails() {
        String yaml = FULL_YAML.replace("password: 'filesecret'", "password: ''");
        DatabaseConfigException e = assertThrows(DatabaseConfigException.class,
                () -> DatabaseConfig.load(section(yaml), noEnv()));
        assertTrue(e.getMessage().contains("password da base de dados não configurada"), e.getMessage());
    }

    @Test
    void load_missingHostNameOrUserFails() {
        for (String key : new String[]{"host", "name", "user"}) {
            StringBuilder sb = new StringBuilder("database:\n");
            for (String line : FULL_YAML.split("\n")) {
                if (line.startsWith("  " + key + ":")) continue;
                if (line.equals("database:")) continue;
                sb.append(line).append("\n");
            }
            DatabaseConfigException e = assertThrows(DatabaseConfigException.class,
                    () -> DatabaseConfig.load(section(sb.toString()), noEnv()), key);
            assertTrue(e.getMessage().contains("database." + key + " em falta"), e.getMessage());
        }
    }

    @Test
    void load_toStringNeverContainsPassword() {
        DatabaseConfig c = DatabaseConfig.load(section(FULL_YAML), noEnv());
        String s = c.toString();
        assertFalse(s.contains("filesecret"), s);
        assertTrue(s.contains("password=***"), s);
        assertTrue(s.contains("db.example.com"), s);
        assertTrue(s.contains("3307"), s);
        assertTrue(s.contains("mydb"), s);
        assertTrue(s.contains("myuser"), s);
    }

    @Test
    void load_invalidPortFails() {
        for (String bad : new String[]{"abc", "0", "70000"}) {
            Map<String, String> env = new HashMap<>();
            env.put("FLAWLESS_DB_PORT", bad);
            DatabaseConfigException e = assertThrows(DatabaseConfigException.class,
                    () -> DatabaseConfig.load(section(FULL_YAML), env), bad);
            assertTrue(e.getMessage().contains("database.port"), e.getMessage());
            assertFalse(e.getMessage().contains("filesecret"), e.getMessage());
        }
    }

    @Test
    void load_sslParsedFromEnv() {
        Map<String, String> env = new HashMap<>();
        env.put("FLAWLESS_DB_SSL", "true");
        assertTrue(DatabaseConfig.load(section(FULL_YAML.replace("ssl: true", "ssl: false")), env).ssl());
        env.put("FLAWLESS_DB_SSL", "FALSE");
        assertFalse(DatabaseConfig.load(section(FULL_YAML), env).ssl());
        env.put("FLAWLESS_DB_SSL", "talvez");
        DatabaseConfigException e = assertThrows(DatabaseConfigException.class,
                () -> DatabaseConfig.load(section(FULL_YAML), env));
        assertTrue(e.getMessage().contains("database.ssl"), e.getMessage());
    }

    @Test
    void jdbcUrl_hasNoCredentials() {
        DatabaseConfig c = DatabaseConfig.load(section(FULL_YAML), noEnv());
        assertEquals("jdbc:mysql://db.example.com:3307/mydb", c.jdbcUrl());
        assertFalse(c.jdbcUrl().contains("filesecret"));
        assertFalse(c.jdbcUrl().contains("myuser"));
        assertFalse(c.jdbcUrl().contains("?"));
    }

    @Test
    void load_passwordWithSpecialCharsIsKeptVerbatim() {
        String special = "p@ss:w/o?r#d %";
        Map<String, String> env = new HashMap<>();
        env.put("FLAWLESS_DB_PASSWORD", special);
        DatabaseConfig fromEnv = DatabaseConfig.load(section(FULL_YAML), env);
        assertEquals(special, fromEnv.password());

        DatabaseConfig fromFile = DatabaseConfig.load(
                section(FULL_YAML.replace("'filesecret'", "'" + special + "'")), noEnv());
        assertEquals(special, fromFile.password());
        assertFalse(fromFile.toString().contains(special));
        assertFalse(fromFile.jdbcUrl().contains(special));
    }
}
