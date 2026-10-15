package pt.flawless.fdatabase;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HikariDatabaseServiceIT {

    @Test
    void supplyAsync_runsOffCallerThread() throws Exception {
        try (HikariDatabaseService service = new HikariDatabaseService(TestDb.config())) {
            String thread = service.supplyAsync(c -> Thread.currentThread().getName()).get();
            assertTrue(thread.startsWith("fdatabase-"), thread);
            assertNotEquals(Thread.currentThread().getName(), thread);
        }
    }

    @Test
    void supplyAsync_sqlExceptionCompletesExceptionally() throws Exception {
        try (HikariDatabaseService service = new HikariDatabaseService(TestDb.config())) {
            CompletableFuture<Integer> future = service.supplyAsync(c -> {
                try (Statement st = c.createStatement()) {
                    st.executeQuery("SELECT * FROM tabela_que_nao_existe_xyz");
                }
                return 1;
            });
            ExecutionException e = assertThrows(ExecutionException.class, future::get);
            assertInstanceOf(SQLException.class, e.getCause());
            assertTrue(future.isCompletedExceptionally());
        }
    }

    @Test
    void runAsync_executesWork() throws Exception {
        try (HikariDatabaseService service = new HikariDatabaseService(TestDb.config())) {
            AtomicInteger result = new AtomicInteger();
            service.runAsync(c -> {
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT 41 + 1")) {
                    rs.next();
                    result.set(rs.getInt(1));
                }
            }).get();
            assertEquals(42, result.get());
        }
    }

    @Test
    void afterClose_asyncCallsCompleteExceptionally() {
        HikariDatabaseService service;
        try {
            service = new HikariDatabaseService(TestDb.config());
        } catch (SQLException e) {
            throw new AssertionError(e);
        }
        service.close();
        CompletableFuture<Integer> supplied = assertDoesNotThrow(() -> service.supplyAsync(c -> 1));
        CompletableFuture<Void> ran = assertDoesNotThrow(() -> service.runAsync(c -> { }));
        assertTrue(supplied.isCompletedExceptionally());
        assertTrue(ran.isCompletedExceptionally());
    }

    @Test
    void close_completesQueuedFuturesWhenTimeoutElapses() throws Exception {
        HikariDatabaseService service = new HikariDatabaseService(TestDb.config(), 500L);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(4);
        List<CompletableFuture<Integer>> running = new ArrayList<>();
        List<CompletableFuture<Integer>> queued = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                running.add(service.supplyAsync(c -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        throw new SQLException("interrompido", e);
                    }
                    return 1;
                }));
            }
            assertTrue(started.await(10, TimeUnit.SECONDS), "as 4 threads deviam estar ocupadas");
            for (int i = 0; i < 3; i++) {
                queued.add(service.supplyAsync(c -> 2));
            }
            Thread closer = new Thread(service::close);
            closer.start();
            for (CompletableFuture<Integer> f : queued) {
                try {
                    f.get(5, TimeUnit.SECONDS);
                    fail("a tarefa em fila não devia ter corrido");
                } catch (TimeoutException e) {
                    fail("future em fila ficou incompleto depois de close()");
                } catch (ExecutionException | CancellationException expected) {
                    // concluído excecionalmente, como exigido
                }
            }
            closer.join(10_000);
            assertFalse(closer.isAlive());
            for (CompletableFuture<Integer> f : running) {
                try {
                    f.get(5, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    fail("future em execução ficou incompleto depois de close()");
                } catch (ExecutionException | CancellationException ignored) {
                    // interrompido por shutdownNow
                }
            }
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test
    void close_isIdempotent() throws Exception {
        HikariDatabaseService service = new HikariDatabaseService(TestDb.config());
        service.close();
        assertDoesNotThrow(service::close);
    }

    @Test
    void connects_withEnvPasswordContainingSpecialChars() throws Exception {
        DatabaseConfig config = TestDb.config();
        assertFalse(config.jdbcUrl().contains(config.password()));
        try (HikariDatabaseService service = new HikariDatabaseService(config)) {
            Integer one = service.supplyAsync((Connection c) -> {
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT 1")) {
                    rs.next();
                    return rs.getInt(1);
                }
            }).get();
            assertEquals(1, one);
        }
    }

    @Test
    void constructor_wrongPassword_reportsDiagnosticsWithoutLeakingAnyPassword() {
        DatabaseConfig real = TestDb.config();
        String wrong = "wrong-pw-" + System.nanoTime() + "-Zq!";
        DatabaseConfig bad = TestDb.configWithPassword(wrong);
        SQLException e = assertThrows(SQLException.class, () -> new HikariDatabaseService(bad));
        String message = e.getMessage();
        assertTrue(message.contains("Access denied"), message);
        assertTrue(message.contains(bad.host() + ":" + bad.port() + "/" + bad.name()), message);
        assertFalse(message.contains(wrong), "a password errada vazou");
        assertFalse(message.contains(real.password()), "a password real vazou");
        assertNull(e.getCause(), "a causa não deve ser encadeada");
    }

    @Test
    void constructor_unreachableHost_reportsRootCauseTypeAndHasNoCause() {
        DatabaseConfig real = TestDb.config();
        java.util.Map<String, String> env = new java.util.HashMap<>(System.getenv());
        env.put("FLAWLESS_DB_PORT", "1");
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("host", "x");
        yaml.set("name", "x");
        yaml.set("user", "x");
        yaml.set("pool.connectionTimeoutMs", 1500);
        DatabaseConfig bad = DatabaseConfig.load(yaml, env);
        SQLException e = assertThrows(SQLException.class, () -> new HikariDatabaseService(bad));
        assertTrue(e.getMessage().contains("(causa: "), e.getMessage());
        assertFalse(e.getMessage().contains(real.password()));
        assertNull(e.getCause());
    }

    private static org.bukkit.plugin.Plugin plugin(String name) {
        return (org.bukkit.plugin.Plugin) java.lang.reflect.Proxy.newProxyInstance(
                HikariDatabaseServiceIT.class.getClassLoader(), new Class<?>[]{org.bukkit.plugin.Plugin.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getName")) return name;
                    if (method.getName().equals("getResource")) return null;
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void migrate_badMigrationName_throwsSqlExceptionWithPath() throws Exception {
        try (HikariDatabaseService service = new HikariDatabaseService(TestDb.config())) {
            SQLException e = assertThrows(SQLException.class,
                    () -> service.migrate(plugin("itplugin"), List.of("db/nao_e_migracao.sql")));
            assertTrue(e.getMessage().contains("db/nao_e_migracao.sql"), e.getMessage());
            assertInstanceOf(IllegalArgumentException.class, e.getCause());
        }
    }

    @Test
    void migrate_nonIncreasingVersions_throwsSqlException() throws Exception {
        try (HikariDatabaseService service = new HikariDatabaseService(TestDb.config())) {
            SQLException e = assertThrows(SQLException.class, () -> service.migrate(plugin("itplugin"),
                    List.of("db/V002__b.sql", "db/V001__a.sql")));
            assertTrue(e.getMessage().contains("db/V001__a.sql"), e.getMessage());
        }
    }

    @Test
    void migrate_versionOverflow_throwsSqlException() throws Exception {
        try (HikariDatabaseService service = new HikariDatabaseService(TestDb.config())) {
            assertThrows(SQLException.class, () -> service.migrate(plugin("itplugin"),
                    List.of("db/V99999999999__big.sql")));
        }
    }

    @Test
    void migrate_nullOwner_throwsNpeNamedOwner() throws Exception {
        try (HikariDatabaseService service = new HikariDatabaseService(TestDb.config())) {
            NullPointerException e = assertThrows(NullPointerException.class,
                    () -> service.migrate(null, List.of("db/V001__a.sql")));
            assertEquals("owner", e.getMessage());
        }
    }
}
