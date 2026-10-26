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

    /**
     * Точность хранения в БД фиксирована и заведомо больше любого
     * разумного fractionalDigits (0..8). В кэше/сервисе — своё scale,
     * БД хранит «как есть», чтобы reload конфига не терял точность.
     */
    private static final int DB_SCALE = 8;

    private final Plugin plugin;
    private final PluginConfig config;
    private SQLManager sqlManager;

    public DatabaseProvider(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public boolean initialize() {
        String datasourceId = config.database.sourceId;

        try {
            this.sqlManager = MineSQL.getRegistry().get(datasourceId);
        } catch (NullPointerException npe) {
            plugin.getLogger().severe(
                    "MineSQL 1.4.2 содержит баг с NPE при отсутствии источника '%s'. "
                            + "Обновите MineSQL до версии с исправлением. Детали: %s"
                            .formatted(datasourceId, npe.getMessage()));
            return false;
        } catch (Throwable t) {
            plugin.getLogger().severe(
                    "Не удалось получить источник данных MineSQL '%s': %s"
                            .formatted(datasourceId, t.getMessage()));
            return false;
        }

        if (this.sqlManager == null) {
            plugin.getLogger().severe(
                    "Не удалось получить источник данных MineSQL '%s'. Проверьте plugins/MineSQL/config.yml"
                            .formatted(datasourceId));
            return false;
        }

        try (Connection c = getConnection()) {
            createTables(c);
            migrateColumnScale(c);
            migrateIndexes(c);
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка инициализации таблиц: " + e.getMessage());
            return false;
        }
    }

    private void createTables(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS players (
                    uuid VARCHAR(36) PRIMARY KEY,
                    name VARCHAR(16),
                    balance DECIMAL(20,%d) NOT NULL DEFAULT 0.00,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """.formatted(DB_SCALE));

            st.execute("""
                CREATE TABLE IF NOT EXISTS pending_payments (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    recipient_uuid VARCHAR(36) NOT NULL,
                    sender_uuid VARCHAR(36) NOT NULL,
                    amount DECIMAL(20,%d) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """.formatted(DB_SCALE));

            st.execute("""
                CREATE TABLE IF NOT EXISTS transactions (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    from_uuid VARCHAR(36),
                    to_uuid VARCHAR(36),
                    amount DECIMAL(20,%d) NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """.formatted(DB_SCALE));
        }
    }

    /**
     * Идемпотентно расширяет DECIMAL-колонки до {@link #DB_SCALE}.
     * Нужно для апгрейда со старых версий, где было DECIMAL(20,2):
     * {@code CREATE TABLE IF NOT EXISTS} не меняет тип существующей таблицы,
     * поэтому без ALTER более точные значения (fractionalDigits > 2)
     * молча округлялись бы при записи.
     */
    private void migrateColumnScale(Connection c) {
        migrateColumn(c, "players", "balance");
        migrateColumn(c, "pending_payments", "amount");
        migrateColumn(c, "transactions", "amount");
    }

    private void migrateColumn(Connection c, String table, String column) {
        try {
            Integer currentScale = readNumericScale(c, table, column);
            if (currentScale == null) {
                // Колонки нет — нечего мигрировать (или таблица ещё не создана).
                return;
            }
            if (currentScale >= DB_SCALE) {
                return;
            }

            String alterSql = "ALTER TABLE " + table + " MODIFY " + column
                    + " DECIMAL(20," + DB_SCALE + ") NOT NULL DEFAULT 0";
            try (Statement st = c.createStatement()) {
                st.execute(alterSql);
                plugin.getLogger().info(
                        "Миграция: %s.%s DECIMAL(20,%d) → DECIMAL(20,%d)"
                                .formatted(table, column, currentScale, DB_SCALE));
            }
        } catch (SQLException e) {
            // ALTER может упасть из-за блокировки или нехватки прав —
            // не фатально, попробуем при следующем старте.
            plugin.getLogger().warning("Не удалось мигрировать %s.%s: %s"
                    .formatted(table, column, e.getMessage()));
        }
    }

    private Integer readNumericScale(Connection c, String table, String column)
            throws SQLException {
        String sql = """
            SELECT NUMERIC_SCALE
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = ?
              AND COLUMN_NAME = ?
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int scale = rs.getInt(1);
                if (rs.wasNull()) return null;
                return scale;
            }
        }
    }

    private void migrateIndexes(Connection c) throws SQLException {
        ensureIndex(c, "players", "idx_players_name",
                "CREATE INDEX idx_players_name ON players(name)");
        ensureIndex(c, "players", "idx_players_balance",
                "CREATE INDEX idx_players_balance ON players(balance)");
        ensureIndex(c, "players", "idx_players_balance_updated",
                "CREATE INDEX idx_players_balance_updated ON players(balance, updated_at)");
        ensureIndex(c, "pending_payments", "idx_pending_recipient_time",
                "CREATE INDEX idx_pending_recipient_time ON pending_payments(recipient_uuid, created_at)");
        ensureIndex(c, "pending_payments", "idx_pending_sender",
                "CREATE INDEX idx_pending_sender ON pending_payments(sender_uuid)");
        ensureIndex(c, "transactions", "idx_tx_from_time",
                "CREATE INDEX idx_tx_from_time ON transactions(from_uuid, created_at)");
        ensureIndex(c, "transactions", "idx_tx_to_time",
                "CREATE INDEX idx_tx_to_time ON transactions(to_uuid, created_at)");
    }

    private void ensureIndex(Connection c, String tableName, String indexName, String createSql)
            throws SQLException {
        if (indexExists(c, tableName, indexName)) return;
        try (Statement st = c.createStatement()) {
            st.execute(createSql);
            plugin.getLogger().info("Создан индекс '%s' на таблице '%s'"
                    .formatted(indexName, tableName));
        } catch (SQLException e) {
            if (e.getErrorCode() == 1061) {
                plugin.getLogger().info(
                        "Индекс '%s' уже существует (создан параллельно).".formatted(indexName));
                return;
            }
            throw e;
        }
    }

    private boolean indexExists(Connection c, String tableName, String indexName)
            throws SQLException {
        String sql = """
            SELECT 1 FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = ?
              AND INDEX_NAME = ?
            LIMIT 1
        """;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
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
        // Пул соединений управляется MineSQL.
    }
}