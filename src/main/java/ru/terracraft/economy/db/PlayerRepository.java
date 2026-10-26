package ru.terracraft.economy.db;

import org.bukkit.plugin.Plugin;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class PlayerRepository {

    private final Plugin plugin;
    private final DatabaseProvider db;
    private final ThreadPoolExecutor executor;

    public PlayerRepository(Plugin plugin, DatabaseProvider db) {
        this.plugin = plugin;
        this.db = db;

        AtomicInteger counter = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(
                4, 4,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1024),
                r -> {
                    Thread t = new Thread(r, "economy-db" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        ) {
            @Override
            protected void afterExecute(Runnable r, Throwable t) {
                if (t != null) {
                    plugin.getLogger().log(Level.WARNING,
                            "economy-db задача завершилась с ошибкой: " + t.getMessage(), t);
                }
            }
        };
        this.executor.allowCoreThreadTimeOut(true);
    }

    /** Возвращает true, если аккаунт существует. */
    public boolean exists(UUID uuid) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("PlayerRepository.exists() failed for " + uuid, e);
        }
    }

    /**
     * Синхронное чтение баланса. Возвращает {@code null}, если аккаунта нет
     * (чтобы вызывающий отличал «нет аккаунта» от «баланс = 0»).
     * Бросает IllegalStateException при ошибке БД.
     */
    public BigDecimal getBalanceOrNull(UUID uuid) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT balance FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getBigDecimal("balance");
            }
            return null;
        } catch (SQLException e) {
            throw new IllegalStateException("PlayerRepository.getBalanceOrNull() failed for " + uuid, e);
        }
    }

    public BigDecimal getBalance(UUID uuid) {
        var b = getBalanceOrNull(uuid);
        return b != null ? b : BigDecimal.ZERO;
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

    /**
     * MariaDB-совместимый upsert.
     * <p>{@code VALUES(col)} — устаревший синтаксис в MySQL 8.0.20+, но
     * <b>рабочий</b> и в MariaDB, и в MySQL. Синтаксис {@code AS new ...
     * new.col} — только MySQL 8.0.19+, MariaDB его <b>не понимает</b>
     * (ошибка 1064). Поэтому используем {@code VALUES()}.
     */
    public void updateBalance(UUID uuid, BigDecimal balance) {
        String sql = "INSERT INTO players (uuid, balance, updated_at) " +
                "VALUES (?, ?, CURRENT_TIMESTAMP) " +
                "ON DUPLICATE KEY UPDATE balance = VALUES(balance), updated_at = CURRENT_TIMESTAMP";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setBigDecimal(2, balance);
            ps.executeUpdate();
        } catch (SQLException e) {
            log("updateBalance", e);
        }
    }

    /**
     * Батч-запись всех балансов одним multi-row upsert.
     * Используется при периодическом flush и при shutdown.
     */
    public void flushAll(Map<UUID, BigDecimal> snapshot) {
        if (snapshot.isEmpty()) return;
        final int chunk = 500;
        var entries = new ArrayList<>(snapshot.entrySet());
        try (Connection c = db.getConnection()) {
            boolean oldAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                for (int from = 0; from < entries.size(); from += chunk) {
                    int to = Math.min(from + chunk, entries.size());
                    upsertChunk(c, entries.subList(from, to));
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(oldAutoCommit);
            }
        } catch (SQLException e) {
            log("flushAll", e);
        }
    }

    private void upsertChunk(Connection c, List<Map.Entry<UUID, BigDecimal>> chunk) throws SQLException {
        var sb = new StringBuilder("INSERT INTO players (uuid, balance, updated_at) VALUES ");
        for (int i = 0; i < chunk.size(); i++) {
            sb.append(i == 0 ? "(?, ?, CURRENT_TIMESTAMP)" : ", (?, ?, CURRENT_TIMESTAMP)");
        }
        sb.append(" ON DUPLICATE KEY UPDATE balance = VALUES(balance), updated_at = CURRENT_TIMESTAMP");

        try (PreparedStatement ps = c.prepareStatement(sb.toString())) {
            int idx = 1;
            for (var e : chunk) {
                ps.setString(idx++, e.getKey().toString());
                ps.setBigDecimal(idx++, e.getValue());
            }
            ps.executeUpdate();
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

    public CompletableFuture<List<Long>> deletePendingPaymentsAndReturnAsync(List<Long> ids) {
        return CompletableFuture.supplyAsync(() -> deletePendingPaymentsAndReturn(ids), executor)
                .exceptionally(ex -> {
                    logFuture("deletePendingPaymentsAndReturnAsync", ex);
                    return List.of();
                });
    }

    // ============ Fire-and-forget async-обёртки ============

    public void updateBalanceAsync(UUID uuid, BigDecimal balance) {
        submit(() -> updateBalance(uuid, balance));
    }

    public void createAsync(UUID uuid, String name, BigDecimal startingBalance) {
        submit(() -> create(uuid, name, startingBalance));
    }

    public void updateNameAsync(UUID uuid, String name) {
        submit(() -> updateName(uuid, name));
    }

    private void submit(Runnable task) {
        try {
            executor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            plugin.getLogger().warning("economy-db очередь переполнена, задача выполнена в вызывающем потоке.");
            task.run();
        }
    }

    // ============ CompletableFuture-обёртки ============

    public CompletableFuture<Boolean> existsAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> exists(uuid), executor)
                .exceptionally(ex -> { logFuture("existsAsync", ex); return false; });
    }

    public CompletableFuture<BigDecimal> getBalanceOrNullAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> getBalanceOrNull(uuid), executor)
                .exceptionally(ex -> {
                    logFuture("getBalanceOrNullAsync", ex);
                    throw new java.util.concurrent.CompletionException(ex);
                });
    }

    public CompletableFuture<List<PendingPayment>> getPendingPaymentsAsync(UUID recipient) {
        return CompletableFuture.supplyAsync(() -> getPendingPayments(recipient), executor)
                .exceptionally(ex -> { logFuture("getPendingPaymentsAsync", ex); return List.of(); });
    }

    public CompletableFuture<String> getNameByUuidAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> getNameByUuid(uuid), executor)
                .exceptionally(ex -> { logFuture("getNameByUuidAsync", ex); return "Unknown"; });
    }

    public CompletableFuture<Void> deletePendingPaymentAsync(long id) {
        return CompletableFuture.runAsync(() -> deletePendingPayment(id), executor)
                .exceptionally(ex -> { logFuture("deletePendingPaymentAsync", ex); return null; });
    }

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

    public List<TopEntry> getTopBalances(int limit) {
        List<TopEntry> result = new ArrayList<>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT name, balance FROM players ORDER BY balance DESC, uuid ASC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    result.add(new TopEntry(name != null ? name : "Unknown", rs.getBigDecimal("balance")));
                }
            }
        } catch (SQLException e) {
            log("getTopBalances", e);
        }
        return List.copyOf(result);
    }

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
                             "WHERE recipient_uuid = ? ORDER BY created_at ASC, id ASC")) {
            ps.setString(1, recipient.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new PendingPayment(
                            rs.getLong("id"),
                            UUID.fromString(rs.getString("sender_uuid")),
                            rs.getBigDecimal("amount")));
                }
            }
        } catch (SQLException e) {
            log("getPendingPayments", e);
        }
        return List.copyOf(result);
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

    /**
     * Идемпотентная доставка: удаляет записи пачки одним DELETE.
     * Возвращает фактически удалённые id — только они должны быть зачислены.
     */
    public List<Long> deletePendingPaymentsAndReturn(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        try (Connection c = db.getConnection()) {
            boolean oldAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                var placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
                var locked = new ArrayList<Long>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id FROM pending_payments WHERE id IN (" + placeholders + ") FOR UPDATE")) {
                    int i = 1;
                    for (Long id : ids) ps.setLong(i++, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) locked.add(rs.getLong("id"));
                    }
                }
                if (!locked.isEmpty()) {
                    var ph2 = String.join(",", java.util.Collections.nCopies(locked.size(), "?"));
                    try (PreparedStatement ps = c.prepareStatement(
                            "DELETE FROM pending_payments WHERE id IN (" + ph2 + ")")) {
                        int i = 1;
                        for (Long id : locked) ps.setLong(i++, id);
                        ps.executeUpdate();
                    }
                }
                c.commit();
                return List.copyOf(locked);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(oldAutoCommit);
            }
        } catch (SQLException e) {
            log("deletePendingPaymentsAndReturn", e);
            return List.of();
        }
    }

    // ============ Логирование транзакций ============

    public void logTransaction(UUID from, UUID to, BigDecimal amount, String type) {
        String sql = "INSERT INTO transactions (from_uuid, to_uuid, amount, type) VALUES (?, ?, ?, ?)";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, from != null ? from.toString() : null);
            ps.setString(2, to != null ? to.toString() : null);
            ps.setBigDecimal(3, amount);
            ps.setString(4, type);
            ps.executeUpdate();
        } catch (SQLException e) {
            log("logTransaction", e);
        }
    }

    public void logTransactionAsync(UUID from, UUID to, BigDecimal amount, String type) {
        submit(() -> logTransaction(from, to, amount, type));
    }

    // ============ Очистка мёртвых аккаунтов ============

    public int deleteInactiveAccounts(int days) {
        String sql = "DELETE FROM players WHERE balance = 0 " +
                "AND updated_at < DATE_SUB(NOW(), INTERVAL ? DAY)";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, days);
            return ps.executeUpdate();
        } catch (SQLException e) {
            log("deleteInactiveAccounts", e);
            return 0;
        }
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    plugin.getLogger().warning(
                            "PlayerRepository: часть задач не завершилась за отведённое время.");
                }
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void log(String method, SQLException e) {
        plugin.getLogger().log(Level.WARNING,
                "PlayerRepository.%s(): %s".formatted(method, e.getMessage()), e);
    }

    private void logFuture(String method, Throwable ex) {
        Throwable cause = (ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null)
                ? ex.getCause() : ex;
        plugin.getLogger().log(Level.WARNING,
                "PlayerRepository.%s() async failed: %s".formatted(method, cause.getMessage()), cause);
    }

    public record PendingPayment(long id, UUID senderUuid, BigDecimal amount) {}
    public record TopEntry(String name, BigDecimal balance) {}
}