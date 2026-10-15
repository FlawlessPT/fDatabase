package pt.flawless.fdatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Applies pending {@link Migration}s using a single connection.
 *
 * <p>MySQL DDL is not transactional (each {@code CREATE}/{@code ALTER} commits implicitly), so auto-commit
 * is left on and a migration that fails halfway may leave the schema partially changed. The version is
 * recorded only after every statement succeeded; therefore migrations must be idempotent
 * ({@code CREATE TABLE IF NOT EXISTS}, ...) so they can safely run again.
 *
 * <p>Concurrent runs (several servers starting at once) are serialised with
 * {@code GET_LOCK('flawless_migrations', 30)}, which is tied to the connection.
 */
public final class MigrationRunner {

    private static final String LOCK_NAME = "flawless_migrations";
    private static final int LOCK_TIMEOUT_SECONDS = 30;

    private final Connection connection;

    public MigrationRunner(Connection connection) {
        this.connection = connection;
    }

    /**
     * @param plugin       plugin name stored in {@code schema_version}
     * @param migrations   migrations in ascending version order
     * @param readResource returns the script content for a path, or {@code null} if it does not exist
     * @throws SQLException if the lock is not obtained, a resource is missing or a statement fails
     */
    public void run(String plugin, List<Migration> migrations, Function<String, String> readResource)
            throws SQLException {
        if (migrations.isEmpty()) {
            return;
        }
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_version ("
                    + "plugin VARCHAR(64) NOT NULL, "
                    + "version INT NOT NULL, "
                    + "applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "PRIMARY KEY (plugin, version)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
        acquireLock();
        try {
            Set<Integer> applied = appliedVersions(plugin);
            for (Migration migration : migrations) {
                if (applied.contains(migration.version())) {
                    continue;
                }
                apply(plugin, migration, readResource);
            }
        } finally {
            releaseLock();
        }
    }

    private void acquireLock() throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT GET_LOCK(?, ?)")) {
            ps.setString(1, LOCK_NAME);
            ps.setInt(2, LOCK_TIMEOUT_SECONDS);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getInt(1) != 1) {
                    throw new SQLException("não foi possível obter o lock das migrações em "
                            + LOCK_TIMEOUT_SECONDS + " segundos");
                }
            }
        }
    }

    private void releaseLock() {
        try (PreparedStatement ps = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            ps.setString(1, LOCK_NAME);
            ps.executeQuery().close();
        } catch (SQLException ignored) {
            // O lock é libertado de qualquer forma quando a ligação fecha; não mascarar o erro original.
        }
    }

    private Set<Integer> appliedVersions(String plugin) throws SQLException {
        Set<Integer> versions = new HashSet<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT version FROM schema_version WHERE plugin = ?")) {
            ps.setString(1, plugin);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    versions.add(rs.getInt(1));
                }
            }
        }
        return versions;
    }

    private void apply(String plugin, Migration migration, Function<String, String> readResource)
            throws SQLException {
        String sql = readResource.apply(migration.path());
        if (sql == null) {
            throw new SQLException("recurso de migração não encontrado: " + migration.path());
        }
        try (Statement st = connection.createStatement()) {
            for (String statement : SqlStatementSplitter.split(sql)) {
                st.execute(statement);
            }
        } catch (SQLException e) {
            throw new SQLException("falha na migração " + migration.path() + ": " + e.getMessage(),
                    e.getSQLState(), e.getErrorCode(), e);
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO schema_version (plugin, version) VALUES (?, ?)")) {
            ps.setString(1, plugin);
            ps.setInt(2, migration.version());
            ps.executeUpdate();
        }
    }
}
