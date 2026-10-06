package ru.net.terracraft.eco.db;

import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class PlayerRepository {

    private final Plugin plugin;
    private final DatabaseProvider db;
    private final ExecutorService executor;

    public PlayerRepository(Plugin plugin, DatabaseProvider db) {
        this.plugin = plugin;
        this.db = db;
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "TerrEconomy-DB");
            t.setDaemon(true);
            return t;
        });
    }

    // ============ Базовые операции ============

    public boolean exists(UUID uuid) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            log("exists", e);
            return false;
        }
    }

    public BigDecimal getBalance(UUID uuid) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT balance FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getBigDecimal("balance");
            }
        } catch (SQLException e) {
            log("getBalance", e);
        }
        return BigDecimal.ZERO;
    }

    public void create(UUID uuid, String name, BigDecimal startingBalance) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO players (uuid, name, balance) VALUES (?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setBigDecimal(3, startingBalance);
            ps.executeUpdate();
        } catch (SQLException e) {
            log("create", e);
        }
    }

    // ============================================================
    //  ⚠ MYSQL MIGRATION POINT
    //  При переключении на MySQL/MariaDB через MineSQL заменить SQL на:
    //
    //      INSERT INTO players (uuid, balance) VALUES (?, ?)
    //      ON DUPLICATE KEY UPDATE balance = VALUES(balance)
    //
    //  Сейчас используется H2-синтаксис MERGE, который в чистом MySQL
    //  не работает.
    // ============================================================
    public void updateBalance(UUID uuid, BigDecimal balance) {
        String sql = "MERGE INTO players (uuid, balance) KEY (uuid) VALUES (?, ?)";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setBigDecimal(2, balance);
            ps.executeUpdate();
        } catch (SQLException e) {
            log("updateBalance", e);
        }
    }

    public void updateName(UUID uuid, String name) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE players SET name = ? WHERE uuid = ?")) {
            ps.setString(1, name);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log("updateName", e);
        }
    }

    // ============ Асинхронные обёртки ============

    public void updateBalanceAsync(UUID uuid, BigDecimal balance) {
        executor.submit(() -> updateBalance(uuid, balance));
    }

    public void createAsync(UUID uuid, String name, BigDecimal startingBalance) {
        executor.submit(() -> create(uuid, name, startingBalance));
    }

    public void updateNameAsync(UUID uuid, String name) {
        executor.submit(() -> updateName(uuid, name));
    }

    // ============ Имя по UUID ============

    public String getNameByUuid(UUID uuid) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT name FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String name = rs.getString("name");
                    return name != null ? name : "Unknown";
                }
            }
        } catch (SQLException e) {
            log("getNameByUuid", e);
        }
        return "Unknown";
    }

    // ============ Топ по балансу ============

    public List<TopEntry> getTopBalances(int limit) {
        List<TopEntry> result = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT name, balance FROM players ORDER BY balance DESC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new TopEntry(
                            rs.getString("name"),
                            rs.getBigDecimal("balance")
                    ));
                }
            }
        } catch (SQLException e) {
            log("getTopBalances", e);
        }
        return result;
    }

    // ============ Офлайн-платежи ============

    public void insertPendingPayment(UUID recipient, UUID sender, BigDecimal amount) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO pending_payments (recipient_uuid, sender_uuid, amount) VALUES (?, ?, ?)")) {
            ps.setString(1, recipient.toString());
            ps.setString(2, sender.toString());
            ps.setBigDecimal(3, amount);
            ps.executeUpdate();
        } catch (SQLException e) {
            log("insertPendingPayment", e);
        }
    }

    public List<PendingPayment> getPendingPayments(UUID recipient) {
        List<PendingPayment> result = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, sender_uuid, amount FROM pending_payments " +
                             "WHERE recipient_uuid = ? ORDER BY created_at ASC")) {
            ps.setString(1, recipient.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new PendingPayment(
                            rs.getLong("id"),
                            UUID.fromString(rs.getString("sender_uuid")),
                            rs.getBigDecimal("amount")
                    ));
                }
            }
        } catch (SQLException e) {
            log("getPendingPayments", e);
        }
        return result;
    }

    public void deletePendingPayment(long id) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM pending_payments WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log("deletePendingPayment", e);
        }
    }

    // ============ Жизненный цикл ============

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ============ Вспомогательные ============

    private void log(String method, SQLException e) {
        plugin.getLogger().log(Level.WARNING, "PlayerRepository." + method + "(): " + e.getMessage(), e);
    }

    // ============ DTO ============

    public record PendingPayment(long id, UUID senderUuid, BigDecimal amount) {}
    public record TopEntry(String name, BigDecimal balance) {}
}