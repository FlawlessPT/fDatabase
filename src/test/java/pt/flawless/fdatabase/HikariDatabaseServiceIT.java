package pt.flawless.fdatabase;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
}
