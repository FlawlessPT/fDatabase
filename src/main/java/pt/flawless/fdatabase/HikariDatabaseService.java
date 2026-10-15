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
import java.util.Objects;
import java.util.concurrent.CancellationException;
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
 *
 * <p>If {@link #close()} gives up waiting and cancels work that was queued but never started, those futures
 * are completed exceptionally with a {@link CancellationException}; running work is interrupted and completes
 * normally (usually exceptionally). No returned future is ever left incomplete.
 */
public final class HikariDatabaseService implements DatabaseService, AutoCloseable {

    private final HikariDataSource dataSource;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final long closeTimeoutMillis;

    /**
     * Opens the pool and verifies a connection.
     *
     * @throws SQLException if no connection can be obtained (the message carries password-free diagnostics of the root cause, never the cause itself)
     */
    public HikariDatabaseService(DatabaseConfig config) throws SQLException {
        this(config, 10_000L);
    }

    /** Package-private: lets tests shorten the time {@link #close()} waits for running work. */
    HikariDatabaseService(DatabaseConfig config, long closeTimeoutMillis) throws SQLException {
        this.closeTimeoutMillis = closeTimeoutMillis;
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("fdatabase");
        hikari.setDriverClassName(com.mysql.cj.jdbc.Driver.class.getName());
        hikari.setJdbcUrl(config.jdbcUrl());
        hikari.setUsername(config.user());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(config.maximumPoolSize());
        hikari.setConnectionTimeout(config.connectionTimeoutMs());
        hikari.setInitializationFailTimeout(config.connectionTimeoutMs());
        // PREFERRED: o MySQL 8.4 gera certificados e caching_sha2_password completa o handshake por TLS;
        // com TLS desligado o driver só consegue autenticar por recuperação da chave pública (recusada).
        hikari.addDataSourceProperty("sslMode", config.ssl() ? "REQUIRED" : "PREFERRED");

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
            // Só texto (sem encadear a causa): a mensagem é redigida para nunca conter a password.
            throw new SQLException("não foi possível ligar à base de dados em " + config.host() + ":"
                    + config.port() + "/" + config.name() + " como " + config.user()
                    + diagnostics(e, config.password()));
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

    /** " (causa: Tipo, SQLState=..., código=..., mensagem)" com a password substituída por {@code ***}. */
    private static String diagnostics(Throwable failure, String password) {
        Throwable root = failure;
        for (int depth = 0; root.getCause() != null && root.getCause() != root && depth < 32; depth++) {
            root = root.getCause();
        }
        StringBuilder text = new StringBuilder(" (causa: ").append(root.getClass().getSimpleName());
        if (root instanceof SQLException) {
            SQLException sql = (SQLException) root;
            text.append(", SQLState=").append(sql.getSQLState()).append(", código=").append(sql.getErrorCode());
        }
        if (root.getMessage() != null) {
            text.append(", ").append(root.getMessage());
        }
        text.append(')');
        String result = text.toString();
        return password == null || password.isEmpty() ? result : result.replace(password, "***");
    }

    @Override
    public <T> CompletableFuture<T> supplyAsync(SqlFunction<Connection, T> work) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(new Task<>(work, future));
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
        Objects.requireNonNull(owner, "owner");
        List<Migration> migrations;
        try {
            migrations = Migrations.parse(resources);
        } catch (IllegalArgumentException e) {
            throw new SQLException(e.getMessage(), e);
        }
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
            if (!executor.awaitTermination(closeTimeoutMillis, TimeUnit.MILLISECONDS)) {
                cancelPending();
            }
        } catch (InterruptedException e) {
            cancelPending();
            Thread.currentThread().interrupt();
        }
        dataSource.close();
    }

    /** Interrupts running work and completes the futures of tasks that never started. */
    private void cancelPending() {
        for (Runnable pending : executor.shutdownNow()) {
            if (pending instanceof Task) {
                ((Task<?>) pending).cancel();
            }
        }
    }

    /** A unit of work that owns the future it must complete, so close() can complete it if it never runs. */
    private final class Task<T> implements Runnable {
        private final SqlFunction<Connection, T> work;
        private final CompletableFuture<T> future;

        Task(SqlFunction<Connection, T> work, CompletableFuture<T> future) {
            this.work = work;
            this.future = future;
        }

        @Override
        public void run() {
            T result;
            try (Connection connection = dataSource.getConnection()) {
                result = work.apply(connection);
            } catch (Throwable t) {
                future.completeExceptionally(t);
                return;
            }
            // Fora do try: a ligação já voltou ao pool quando os callbacks síncronos correm.
            future.complete(result);
        }

        void cancel() {
            future.completeExceptionally(
                    new CancellationException("serviço de base de dados encerrado antes de a tarefa começar"));
        }
    }
}
