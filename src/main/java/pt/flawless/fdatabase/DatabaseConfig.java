package pt.flawless.fdatabase;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Map;

/** Configuração imutável da ligação à base de dados (ficheiro + variáveis de ambiente FLAWLESS_DB_*). */
public final class DatabaseConfig {

    private static final int DEFAULT_POOL_SIZE = 8;
    private static final int DEFAULT_TIMEOUT_MS = 10000;

    private final String host;
    private final int port;
    private final String name;
    private final String user;
    private final String password;
    private final boolean ssl;
    private final int maximumPoolSize;
    private final int connectionTimeoutMs;

    private DatabaseConfig(String host, int port, String name, String user, String password,
                           boolean ssl, int maximumPoolSize, int connectionTimeoutMs) {
        this.host = host;
        this.port = port;
        this.name = name;
        this.user = user;
        this.password = password;
        this.ssl = ssl;
        this.maximumPoolSize = maximumPoolSize;
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    /**
     * @param database secção {@code database} do config.yml (pode ser null)
     * @param env      variáveis de ambiente; quando presentes e não vazias ganham ao ficheiro
     */
    public static DatabaseConfig load(ConfigurationSection database, Map<String, String> env)
            throws DatabaseConfigException {
        String host = required(pick(env, "FLAWLESS_DB_HOST", database, "host"), "database.host");
        String portText = pick(env, "FLAWLESS_DB_PORT", database, "port");
        int port = portText == null ? 3306 : parseInt(portText, "database.port");
        if (port < 1 || port > 65535) {
            throw new DatabaseConfigException("database.port inválida (1 a 65535)");
        }
        String name = required(pick(env, "FLAWLESS_DB_NAME", database, "name"), "database.name");
        String user = required(pick(env, "FLAWLESS_DB_USER", database, "user"), "database.user");
        String password = pick(env, "FLAWLESS_DB_PASSWORD", database, "password");
        if (password == null) {
            throw new DatabaseConfigException(
                    "password da base de dados não configurada (database.password ou FLAWLESS_DB_PASSWORD)");
        }
        String sslText = pick(env, "FLAWLESS_DB_SSL", database, "ssl");
        boolean ssl = sslText != null && parseBoolean(sslText);

        int pool = positive(fileValue(database, "pool.maximumPoolSize"), DEFAULT_POOL_SIZE,
                "database.pool.maximumPoolSize");
        int timeout = positive(fileValue(database, "pool.connectionTimeoutMs"), DEFAULT_TIMEOUT_MS,
                "database.pool.connectionTimeoutMs");
        return new DatabaseConfig(host, port, name, user, password, ssl, pool, timeout);
    }

    private static String fileValue(ConfigurationSection section, String key) {
        if (section == null || !section.contains(key)) return null;
        Object value = section.get(key);
        return value == null ? null : value.toString();
    }

    /** Variável de ambiente (se presente e não vazia), senão valor do ficheiro (se não vazio), senão null. */
    private static String pick(Map<String, String> env, String envKey, ConfigurationSection section, String key) {
        String fromEnv = env == null ? null : env.get(envKey);
        if (fromEnv != null && !fromEnv.isEmpty()) return fromEnv;
        String fromFile = fileValue(section, key);
        return fromFile == null || fromFile.isEmpty() ? null : fromFile;
    }

    private static String required(String value, String key) {
        if (value == null || value.trim().isEmpty()) {
            throw new DatabaseConfigException(key + " em falta");
        }
        return value;
    }

    private static int parseInt(String text, String key) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new DatabaseConfigException(key + " inválida (esperado um número inteiro)");
        }
    }

    private static boolean parseBoolean(String text) {
        String t = text.trim();
        if (t.equalsIgnoreCase("true")) return true;
        if (t.equalsIgnoreCase("false")) return false;
        throw new DatabaseConfigException("database.ssl inválido (esperado true ou false)");
    }

    private static int positive(String text, int fallback, String key) {
        if (text == null || text.trim().isEmpty()) return fallback;
        int value = parseInt(text, key);
        if (value <= 0) {
            throw new DatabaseConfigException(key + " tem de ser maior que 0");
        }
        return value;
    }

    public String host() { return host; }

    public int port() { return port; }

    public String name() { return name; }

    public String user() { return user; }

    public String password() { return password; }

    public boolean ssl() { return ssl; }

    public int maximumPoolSize() { return maximumPoolSize; }

    public int connectionTimeoutMs() { return connectionTimeoutMs; }

    /** URL JDBC sem credenciais nem query string. */
    public String jdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + name;
    }

    @Override
    public String toString() {
        return "DatabaseConfig{host=" + host + ", port=" + port + ", name=" + name + ", user=" + user
                + ", password=***, ssl=" + ssl + ", maximumPoolSize=" + maximumPoolSize
                + ", connectionTimeoutMs=" + connectionTimeoutMs + "}";
    }
}
