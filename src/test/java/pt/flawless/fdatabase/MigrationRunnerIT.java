package pt.flawless.fdatabase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;

import static org.junit.jupiter.api.Assertions.*;

class MigrationRunnerIT {

    private static final String PLUGIN = "itplugin";
    private static final String V1 = "db/V001__a.sql";
    private static final String V2 = "db/V002__b.sql";

    private HikariDatabaseService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new HikariDatabaseService(TestDb.config());
        service.supplyAsync(c -> {
            TestDb.dropTables(c, "it_a", "it_b");
            return null;
        }).get();
    }

    @AfterEach
    void tearDown() {
        if (service != null) service.close();
    }

    private static Map<String, String> files() {
        Map<String, String> files = new HashMap<>();
        files.put(V1, "CREATE TABLE IF NOT EXISTS it_a (id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;\n");
        files.put(V2, "CREATE TABLE IF NOT EXISTS it_b (id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;\n"
                + "INSERT INTO it_b (id) VALUES (1);\n");
        return files;
    }

    private static List<Migration> migrations() {
        return Migrations.parse(List.of(V1, V2));
    }

    private static int count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static String versions() {
        return "SELECT COUNT(*) FROM schema_version WHERE plugin='" + PLUGIN + "'";
    }

    @Test
    void run_appliesPendingMigrationsInOrderOnce() throws Exception {
        Map<String, String> files = files();
        service.supplyAsync(c -> {
            MigrationRunner runner = new MigrationRunner(c);
            runner.run(PLUGIN, migrations(), files::get);
            runner.run(PLUGIN, migrations(), files::get);
            assertEquals(2, count(c, versions()));
            assertEquals(0, count(c, "SELECT COUNT(*) FROM it_a"));
            assertEquals(1, count(c, "SELECT COUNT(*) FROM it_b"));
            return null;
        }).get();
    }

    @Test
    void run_emptyListDoesNothing() throws Exception {
        service.supplyAsync(c -> {
            new MigrationRunner(c).run(PLUGIN, List.of(), p -> {
                throw new AssertionError("não devia ler recursos");
            });
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SHOW TABLES LIKE 'schema_version'")) {
                assertFalse(rs.next());
            }
            return null;
        }).get();
    }

    @Test
    void run_missingResourceFailsWithPath() throws Exception {
        service.supplyAsync(c -> {
            SQLException e = assertThrows(SQLException.class,
                    () -> new MigrationRunner(c).run(PLUGIN, migrations(), p -> null));
            assertTrue(e.getMessage().contains(V1), e.getMessage());
            assertEquals(0, count(c, "SELECT COUNT(*) FROM schema_version"));
            return null;
        }).get();
    }

    @Test
    void run_failedMigrationDoesNotRecordVersion() throws Exception {
        Map<String, String> fixed = files();
        Map<String, String> broken = files();
        broken.put(V2, "CREATE TABLE IF NOT EXISTS it_b (id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB;\n"
                + "THIS IS NOT SQL;\n");
        service.supplyAsync(c -> {
            assertThrows(SQLException.class, () -> new MigrationRunner(c).run(PLUGIN, migrations(), broken::get));
            assertEquals(1, count(c, versions()));
            assertEquals(0, count(c, "SELECT COUNT(*) FROM schema_version WHERE version=2"));
            new MigrationRunner(c).run(PLUGIN, migrations(), fixed::get);
            assertEquals(2, count(c, versions()));
            assertEquals(1, count(c, "SELECT COUNT(*) FROM it_b"));
            return null;
        }).get();
    }

    @Test
    void run_concurrentRunsApplyOnce() throws Exception {
        Map<String, String> slow = files();
        slow.put(V1, slow.get(V1) + "DO SLEEP(1);\n");
        CyclicBarrier barrier = new CyclicBarrier(2);
        // Dois trabalhos em threads diferentes do executor: cada um usa a sua própria ligação do pool.
        CompletableFuture<Void> a = service.runAsync(c -> run(barrier, c, slow));
        CompletableFuture<Void> b = service.runAsync(c -> run(barrier, c, slow));
        a.get();
        b.get();
        service.supplyAsync(c -> {
            assertEquals(2, count(c, versions()));
            assertEquals(1, count(c, "SELECT COUNT(*) FROM it_b"));
            return null;
        }).get();
    }

    private static void run(CyclicBarrier barrier, Connection c, Map<String, String> files) throws SQLException {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new SQLException("barreira", e);
        }
        new MigrationRunner(c).run(PLUGIN, migrations(), files::get);
    }
}
