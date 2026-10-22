package ru.terracraft.economy.db;

import cc.carm.plugin.minesql.MineSQL;
import cc.carm.lib.easysql.api.SQLManager;
import org.bukkit.plugin.Plugin;
import ru.terracraft.economy.config.PluginConfig;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseProvider {

    private final Plugin plugin;
    private final PluginConfig config;
    private SQLManager sqlManager;

    public DatabaseProvider(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    private String getDatasourceId() {
        return config.database.sourceId;
    }

    public boolean initialize() {
        String datasourceId = getDatasourceId();
        this.sqlManager = MineSQL.getRegistry().get(datasourceId);

        if (this.sqlManager == null) {
            plugin.getLogger().severe(
                    "Не удалось получить источник данных MineSQL '%s'. Проверьте plugins/MineSQL/config.yml"
                            .formatted(datasourceId));
            return false;
        }

        try {
            createTables();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка инициализации таблиц: " + e.getMessage());
            return false;
        }
    }

    private void createTables() throws SQLException {
        try (Connection c = getConnection();
             Statement st = c.createStatement()) {

            st.execute("""
                CREATE TABLE IF NOT EXISTS players (
                    uuid VARCHAR(36) PRIMARY KEY,
                    name VARCHAR(16),
                    balance DECIMAL(20,2) NOT NULL DEFAULT 0.00,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_players_name ON players(name)");

            st.execute("""
                CREATE TABLE IF NOT EXISTS pending_payments (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    recipient_uuid VARCHAR(36) NOT NULL,
                    sender_uuid VARCHAR(36) NOT NULL,
                    amount DECIMAL(20,2) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_pending_recipient ON pending_payments(recipient_uuid)");

            st.execute("""
                CREATE TABLE IF NOT EXISTS transactions (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    from_uuid VARCHAR(36),
                    to_uuid VARCHAR(36),
                    amount DECIMAL(20,2) NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_tx_from (from_uuid),
                    INDEX idx_tx_to (to_uuid)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
        }
    }

    public Connection getConnection() throws SQLException {
        if (sqlManager == null) {
            throw new SQLException("MineSQL не инициализирован.");
        }
        return sqlManager.getConnection();
    }

    public void close() {
        // Пул соединений управляется MineSQL — здесь ничего не закрываем.
    }
}