package pt.flawless.fdatabase;

import java.sql.SQLException;

import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import pt.flawless.fapi.database.DatabaseService;
import pt.flawless.fapi.logs.FConsoleLogger;

public final class FDatabase extends JavaPlugin {

    private HikariDatabaseService service;
    private final FConsoleLogger consoleLogger = new FConsoleLogger(this.getName());

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            DatabaseConfig config = DatabaseConfig.load(getConfig().getConfigurationSection("database"), System.getenv());
            service = new HikariDatabaseService(config);
            getServer().getServicesManager().register(DatabaseService.class, service, this, ServicePriority.Normal);
            getLogger().info("serviço de base de dados registado (" + config.host() + ":" + config.port() + "/" + config.name() + ")");
            consoleLogger.sendEnablePluginMessage();
        } catch (DatabaseConfigException | SQLException e) {
            // As mensagens destas exceções não contêm a password; não se regista o stack trace.
            getLogger().severe("não foi possível iniciar a base de dados: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (service == null) {
            return;
        }
        getServer().getServicesManager().unregister(service);
        service.close();
        service = null;
        consoleLogger.sendDisablePluginMessage();
    }
}
