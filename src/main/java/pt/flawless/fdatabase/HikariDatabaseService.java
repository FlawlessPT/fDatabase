package pt.flawless.fdatabase;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.Plugin;
import pt.flawless.fapi.database.DatabaseService;
import pt.flawless.fapi.database.SqlConsumer;
import pt.flawless.fapi.database.SqlFunction;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link DatabaseService} backed by a HikariCP pool and a private daemon thread pool ({@code fdatabase-N}).
 *
 * <p>Failure contract of {@link #supplyAsync}/{@link #runAsync}: the returned future is completed
 * exceptionally with the original exception from the work (typically the {@link SQLException} itself, so
 * {@code get()} exposes it as the {@code ExecutionException} cause and {@code join()} as the
 * {@code CompletionException} cause). If the executor rejects the task (for instance after {@link #close()})
 * the future is completed exceptionally with the {@link RejectedExecutionException}; the methods never throw.
 */
public final class HikariDatabaseService implements DatabaseService, AutoCloseable {

    private final HikariDataSource dataSource;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Opens the pool and verifies a connection.
     *
     * @throws SQLException if no connection can be obtained (the message never contains the password)
     */
    public HikariDatabaseService(DatabaseConfig config) throws SQLException {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("fdatabase");
        hikari.setDriverClassName(com.mysql.cj.jdbc.Driver.class.getName());
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.user());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(config.maximumPoolSize());
        hikari.setConnectionTimeout(config.connectionTimeoutMs());
        hikari.setInitializationFailTimeout(config.connectionTimeoutMs());
        hikari.addDataSourceProperty("sslMode", config.ssl() ? "REQUIRED" : "DISABLED");

        HikariDataSource ds = null;
        try {
            ds = new HikariDataSource(hikari);
            try (Connection ignored = ds.getConnection()) {
                // ligação verificada
            }
        } catch (Exception e) {
            if (ds != null) {
                ds.close();
            }
            // A mensagem da causa pode ecoar credenciais: não é propagada.
            throw new SQLException("não foi possível ligar à base de dados em " + config.host() + ":"
                    + config.port() + "/" + config.name() + " como " + config.user());
        }
        this.dataSource = ds;

        AtomicInteger counter = new AtomicInteger();
        int threads = Math.min(4, config.maximumPoolSize());
        this.executor = new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "fdatabase-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.executor.allowCoreThreadTimeOut(true);
    }

    @Override
    public <T> CompletableFuture<T> supplyAsync(SqlFunction<Connection, T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    future.complete(work.apply(connection));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }

    @Override
    public CompletableFuture<Void> runAsync(SqlConsumer<Connection> work) {
        return supplyAsync(connection -> {
            work.accept(connection);
            return null;
        });
    }

    @Override
    public void migrate(Plugin owner, List<String> resources) throws SQLException {
        List<Migration> migrations = Migrations.parse(resources);
        if (migrations.isEmpty()) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            new MigrationRunner(connection).run(owner.getName(), migrations, path -> readResource(owner, path));
        }
    }

    private static String readResource(Plugin owner, String path) {
        try (InputStream in = owner.getResource(path)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Idempotent: stops accepting work, waits up to 10 s for running work, then closes the pool. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        dataSource.close();
    }
}
