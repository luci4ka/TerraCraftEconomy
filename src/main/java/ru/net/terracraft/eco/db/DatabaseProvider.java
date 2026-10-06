package ru.net.terracraft.eco.db;

import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.Plugin;
import ru.net.terracraft.eco.config.PluginConfig;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Единая точка получения JDBC-соединений.
 * Сейчас — H2 + HikariCP.
 * Позже можно заменить внутренности на MineSQL без изменения остального кода.
 */
public class DatabaseProvider {

    private final Plugin plugin;
    private final PluginConfig config;
    private HikariDataSource dataSource;

    public DatabaseProvider(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    // ============================================================
    //  ⚠ MYSQL MIGRATION POINT
    //  При переходе на MineSQL заменить тело initialize() на:
    //
    //      public boolean initialize() {
    //          try {
    //              MineSQL.getRegistry().get("data-source-id").getConnection().close();
    //              createTables();
    //              return true;
    //          } catch (Exception e) {
    //              plugin.getLogger().severe("MineSQL недоступен: " + e.getMessage());
    //              return false;
    //          }
    //      }
    // ============================================================
    public boolean initialize() {
        try {
            File dbFile = new File(plugin.getDataFolder(), config.database.file);
            String jdbcUrl = "jdbc:h2:" + dbFile.getAbsolutePath() + ";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE";

            com.zaxxer.hikari.HikariConfig hc = new com.zaxxer.hikari.HikariConfig();
            hc.setJdbcUrl(jdbcUrl);
            hc.setUsername("sa");
            hc.setPassword("");
            hc.setMaximumPoolSize(config.database.poolSize);
            hc.setMinimumIdle(1);
            hc.setConnectionTimeout(config.database.connectionTimeout);
            hc.setPoolName("TerrEconomyPool");
            hc.setDriverClassName("org.h2.Driver");

            this.dataSource = new HikariDataSource(hc);

            createTables();
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe("Ошибка H2: " + e.getMessage());
            return false;
        }
    }

    // ============================================================
    //  ⚠ MYSQL MIGRATION POINT
    //  Синтаксис CREATE TABLE совместим с MySQL, но:
    //  - DECIMAL(20,2) — ок
    //  - VARCHAR(36) — ок
    //  - BIGINT AUTO_INCREMENT — ок
    //  - TIMESTAMP DEFAULT CURRENT_TIMESTAMP — ок
    //  Всё это MySQL тоже понимает, но проверь кодировку (utf8mb4).
    // ============================================================
    private void createTables() throws SQLException {
        try (Connection c = getConnection();
             Statement st = c.createStatement()) {

            st.execute("""
                CREATE TABLE IF NOT EXISTS players (
                    uuid VARCHAR(36) PRIMARY KEY,
                    name VARCHAR(16),
                    balance DECIMAL(20,2) NOT NULL DEFAULT 0.00,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_players_name ON players(name)");

            st.execute("""
                CREATE TABLE IF NOT EXISTS pending_payments (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    recipient_uuid VARCHAR(36) NOT NULL,
                    sender_uuid VARCHAR(36) NOT NULL,
                    amount DECIMAL(20,2) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_pending_recipient ON pending_payments(recipient_uuid)");
        }
    }

    // ============================================================
    //  ⚠ MYSQL MIGRATION POINT
    //  При переходе на MineSQL заменить тело метода на:
    //
    //      public Connection getConnection() throws SQLException {
    //          return MineSQL.getRegistry().get("data-source-id").getConnection();
    //      }
    // ============================================================
    /** Единственный метод, который используют все репозитории. */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}