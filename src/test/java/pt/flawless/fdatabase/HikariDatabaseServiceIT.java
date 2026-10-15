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
}
