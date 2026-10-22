package ru.terracraft.economy.db;

import cc.carm.plugin.minesql.MineSQL;
import cc.carm.lib.easysql.api.SQLManager;
import org.bukkit.plugin.Plugin;
import ru.terracraft.economy.config.PluginConfig;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
            migrateIndexes();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка инициализации таблиц: " + e.getMessage());
            return false;
        }
    }

    private void createTables() throws SQLException {
        try (Connection c = getConnection();
             Statement st = c.createStatement()) {

            // --- players ---
            st.execute("""
                CREATE TABLE IF NOT EXISTS players (
                    uuid VARCHAR(36) PRIMARY KEY,
                    name VARCHAR(16),
                    balance DECIMAL(20,2) NOT NULL DEFAULT 0.00,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

            // --- pending_payments ---
            st.execute("""
                CREATE TABLE IF NOT EXISTS pending_payments (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    recipient_uuid VARCHAR(36) NOT NULL,
                    sender_uuid VARCHAR(36) NOT NULL,
                    amount DECIMAL(20,2) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);

            // --- transactions ---
            st.execute("""
                CREATE TABLE IF NOT EXISTS transactions (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    from_uuid VARCHAR(36),
                    to_uuid VARCHAR(36),
                    amount DECIMAL(20,2) NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
        }
    }

    /**
     * Идемпотентная миграция индексов: проверяем наличие каждого индекса
     * через information_schema.STATISTICS и создаём только отсутствующие.
     * Работает как для свежих, так и для уже развёрнутых баз.
     */
    private void migrateIndexes() throws SQLException {
        // players: поиск по нику
        ensureIndex("players", "idx_players_name",
                "CREATE INDEX idx_players_name ON players(name)");

        // players: ORDER BY balance DESC для топов
        ensureIndex("players", "idx_players_balance",
                "CREATE INDEX idx_players_balance ON players(balance)");

        // players: очистка неактивных (WHERE balance = 0 AND updated_at < ...)
        ensureIndex("players", "idx_players_balance_updated",
                "CREATE INDEX idx_players_balance_updated ON players(balance, updated_at)");

        // pending_payments: составной (recipient_uuid, created_at) для выборки
        // платежей получателя с сортировкой по времени
        ensureIndex("pending_payments", "idx_pending_recipient_time",
                "CREATE INDEX idx_pending_recipient_time " +
                        "ON pending_payments(recipient_uuid, created_at)");
        // отдельный индекс для поиска платежей от отправителя
        ensureIndex("pending_payments", "idx_pending_sender",
                "CREATE INDEX idx_pending_sender ON pending_payments(sender_uuid)");

        // transactions: составные индексы (uuid, created_at) покрывают
        // и простой поиск по UUID, и сортированную выборку истории
        ensureIndex("transactions", "idx_tx_from_time",
                "CREATE INDEX idx_tx_from_time ON transactions(from_uuid, created_at)");
        ensureIndex("transactions", "idx_tx_to_time",
                "CREATE INDEX idx_tx_to_time ON transactions(to_uuid, created_at)");
    }

    /**
     * Проверяет наличие индекса в текущей схеме (DATABASE()) и создаёт его
     * при отсутствии. Учитывает, что индекс может быть составным — проверяем
     * наличие записи по имени индекса, а не по конкретному столбцу.
     */
    private void ensureIndex(String tableName, String indexName, String createSql)
            throws SQLException {

        if (indexExists(tableName, indexName)) {
            return;
        }

        try (Connection c = getConnection();
             Statement st = c.createStatement()) {
            st.execute(createSql);
            plugin.getLogger().info("Создан индекс '%s' на таблице '%s'"
                    .formatted(indexName, tableName));
        } catch (SQLException e) {
            // Гонка при параллельном старте (например, несколько инстансов
            // на одной БД) — если индекс уже создан, ошибка 1061 безопасна.
            if (e.getErrorCode() == 1061) { // ER_DUP_KEYNAME
                plugin.getLogger().info(
                        "Индекс '%s' уже существует (создан параллельно)."
                                .formatted(indexName));
                return;
            }
            throw e;
        }
    }

    /**
     * Проверяет наличие индекса в текущей базе данных через
     * information_schema.STATISTICS.
     */
    private boolean indexExists(String tableName, String indexName)
            throws SQLException {

        String sql = """
            SELECT 1
            FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = ?
              AND INDEX_NAME = ?
            LIMIT 1
        """;

        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ps.setString(2, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
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