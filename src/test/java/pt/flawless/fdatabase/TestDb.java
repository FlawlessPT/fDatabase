package pt.flawless.fdatabase;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Assumptions;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/** Ajudante dos testes de integração: só corre contra a base literalmente chamada flawless_test. */
final class TestDb {

    private static final String[] VARS = {"FLAWLESS_DB_HOST", "FLAWLESS_DB_PORT", "FLAWLESS_DB_NAME",
            "FLAWLESS_DB_USER", "FLAWLESS_DB_PASSWORD"};

    private TestDb() {
    }

    /** Ignora o teste se as variáveis faltarem ou se a base não for flawless_test (os testes fazem DROP). */
    static DatabaseConfig config() {
        return config(null);
    }

    /** Como {@link #config()} mas com outra password (para testar ligações falhadas). */
    static DatabaseConfig configWithPassword(String password) {
        return config(password);
    }

    private static DatabaseConfig config(String passwordOverride) {
        Map<String, String> env = new java.util.HashMap<>(System.getenv());
        for (String v : VARS) {
            String value = env.get(v);
            Assumptions.assumeTrue(value != null && !value.isEmpty(), v + " não definida");
        }
        Assumptions.assumeTrue("flawless_test".equals(env.get("FLAWLESS_DB_NAME")),
                "FLAWLESS_DB_NAME não é flawless_test");
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("host", "placeholder");
        yaml.set("port", 3306);
        yaml.set("name", "placeholder");
        yaml.set("user", "placeholder");
        if (passwordOverride != null) {
            env.put("FLAWLESS_DB_PASSWORD", passwordOverride);
        }
        return DatabaseConfig.load(yaml, env);
    }

    static void dropTables(Connection connection, String... tables) throws SQLException {
        try (Statement st = connection.createStatement()) {
            for (String table : tables) {
                st.execute("DROP TABLE IF EXISTS " + table);
            }
            st.execute("DROP TABLE IF EXISTS schema_version");
        }
    }
}
