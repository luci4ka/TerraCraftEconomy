package ru.terracraft.economy.db;

import org.bukkit.plugin.Plugin;
import ru.terracraft.economy.config.PluginConfig;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public class PlayerRepository {

    private final Plugin plugin;
    private final DatabaseProvider db;
    private final PluginConfig config;
    private final ThreadPoolExecutor executor;

    private final AtomicLong droppedTasks = new AtomicLong();

    public PlayerRepository(Plugin plugin, DatabaseProvider db, PluginConfig config) {
        this.plugin = plugin;
        this.db = db;
        this.config = config;

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

    // ============ Retry на transient SQL ============

    private <T> T withRetry(String opName, Callable<T> op) {
        int maxAttempts = Math.max(1, config.database.retryMaxAttempts);
        long baseDelay = Math.max(10L, config.database.retryBaseDelayMs);

        SQLException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return op.call();
            } catch (SQLException e) {
                if (!isTransient(e) || attempt == maxAttempts) {
                    throw new IllegalStateException(opName + " failed after " + attempt + " attempts", e);
                }
                last = e;
                long delay = baseDelay * (long) Math.pow(3, attempt - 1);
                plugin.getLogger().warning(
                        "PlayerRepository.%s transient SQL (attempt %d/%d): %s — retry in %dms"
                                .formatted(opName, attempt, maxAttempts, e.getMessage(), delay));
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(opName + " interrupted", ie);
                }
            } catch (Exception e) {
                throw new IllegalStateException(opName + " failed", e);
            }
        }
        throw new IllegalStateException(opName + " failed", last);
    }

    private static boolean isTransient(SQLException e) {
        String state = e.getSQLState();
        if ("40001".equals(state)) return true;
        int code = e.getErrorCode();
        return code == 1205 || code == 1213;
    }

    // ============ Чтение ============

    public boolean exists(UUID uuid) {
        return withRetry("exists", () -> {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT 1 FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    public BigDecimal getBalanceOrNull(UUID uuid) {
        return withRetry("getBalanceOrNull", () -> {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT balance FROM players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getBigDecimal("balance");
                }
                return null;
            }
        });
    }

    public BigDecimal getBalance(UUID uuid) {
        var b = getBalanceOrNull(uuid);
        return b != null ? b : BigDecimal.ZERO;
    }

    // ============ Запись ============

    public void create(UUID uuid, String name, BigDecimal startingBalance) {
        withRetry("create", () -> {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO players (uuid, name, balance) VALUES (?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, name);
                ps.setBigDecimal(3, startingBalance);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void updateBalance(UUID uuid, BigDecimal balance) {
        withRetry("updateBalance", () -> {
            upsertBalance(c -> {}, uuid, balance);
            return null;
        });
    }

    /**
     * Атомарно: upsert баланса + запись в {@code transactions} в одной транзакции.
     */
    public void persistBalanceChange(UUID uuid, BigDecimal newBalance,
                                     BigDecimal amount, String type) {
        String upsert = "INSERT INTO players (uuid, balance, updated_at) " +
                "VALUES (?, ?, CURRENT_TIMESTAMP) " +
                "ON DUPLICATE KEY UPDATE balance = VALUES(balance), updated_at = CURRENT_TIMESTAMP";
        String tx = "INSERT INTO transactions (from_uuid, to_uuid, amount, type) VALUES (?, ?, ?, ?)";

        withRetry("persistBalanceChange", () -> {
            try (Connection c = db.getConnection()) {
                boolean oldAutoCommit = c.getAutoCommit();
                c.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = c.prepareStatement(upsert)) {
                        ps.setString(1, uuid.toString());
                        ps.setBigDecimal(2, newBalance);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = c.prepareStatement(tx)) {
                        // Универсально: from = null, to = uuid. Для WITHDRAW
                        // порядок в аудите некритичен — фильтровать по type.
                        ps.setString(1, null);
                        ps.setString(2, uuid.toString());
                        ps.setBigDecimal(3, amount);
                        ps.setString(4, type);
                        ps.executeUpdate();
                    }
                    c.commit();
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(oldAutoCommit);
                }
            }
            return null;
        });
    }

    /**
     * Атомарно: вставка pending-платежа + запись в {@code transactions} (PAY_QUEUED).
     */
    public void persistPendingPayment(UUID recipient, UUID sender, BigDecimal amount) {
        String insert = "INSERT INTO pending_payments (recipient_uuid, sender_uuid, amount) VALUES (?, ?, ?)";
        String tx = "INSERT INTO transactions (from_uuid, to_uuid, amount, type) VALUES (?, ?, ?, ?)";

        withRetry("persistPendingPayment", () -> {
            try (Connection c = db.getConnection()) {
                boolean oldAutoCommit = c.getAutoCommit();
                c.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = c.prepareStatement(insert)) {
                        ps.setString(1, recipient.toString());
                        ps.setString(2, sender.toString());
                        ps.setBigDecimal(3, amount);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = c.prepareStatement(tx)) {
                        ps.setString(1, sender.toString());
                        ps.setString(2, recipient.toString());
                        ps.setBigDecimal(3, amount);
                        ps.setString(4, "PAY_QUEUED");
                        ps.executeUpdate();
                    }
                    c.commit();
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(oldAutoCommit);
                }
            }
            return null;
        });
    }

    // ============ persistTransfer ============

    public void persistTransfer(UUID from, BigDecimal fromBalance,
                                UUID to, BigDecimal toBalance,
                                BigDecimal debit, String type, BigDecimal tax) {
        String upsert = "INSERT INTO players (uuid, balance, updated_at) " +
                "VALUES (?, ?, CURRENT_TIMESTAMP) " +
                "ON DUPLICATE KEY UPDATE balance = VALUES(balance), updated_at = CURRENT_TIMESTAMP";
        String tx = "INSERT INTO transactions (from_uuid, to_uuid, amount, type) VALUES (?, ?, ?, ?)";

        withRetry("persistTransfer", () -> {
            try (Connection c = db.getConnection()) {
                boolean oldAutoCommit = c.getAutoCommit();
                c.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = c.prepareStatement(upsert)) {
                        ps.setString(1, from.toString());
                        ps.setBigDecimal(2, fromBalance);
                        ps.executeUpdate();

                        ps.setString(1, to.toString());
                        ps.setBigDecimal(2, toBalance);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = c.prepareStatement(tx)) {
                        ps.setString(1, from.toString());
                        ps.setString(2, to.toString());
                        ps.setBigDecimal(3, debit);
                        ps.setString(4, type);
                        ps.executeUpdate();

                        if (tax != null && tax.signum() > 0) {
                            ps.setString(1, from.toString());
                            ps.setString(2, null);
                            ps.setBigDecimal(3, tax);
                            ps.setString(4, "TAX");
                            ps.executeUpdate();
                        }
                    }
                    c.commit();
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(oldAutoCommit);
                }
            }
            return null;
        });
    }

    // ============ flushAll (boolean) ============

    /**
     * Батч-запись балансов. Возвращает {@code true}, если всё применилось
     * (или snapshot пуст); {@code false} — при сбое БД (после retry).
     * Вызывающий не должен очищать dirty-set при {@code false}.
     */
    public boolean flushAll(Map<UUID, BigDecimal> snapshot) {
        if (snapshot.isEmpty()) return true;
        final int chunk = 500;
        var entries = new ArrayList<>(snapshot.entrySet());
        entries.sort(Comparator.comparing(e -> e.getKey().toString()));

        try {
            withRetry("flushAll", () -> {
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
                }
                return null;
            });
            return true;
        } catch (RuntimeException e) {
            log("flushAll", e);
            return false;
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

    // ============ updateName ============

    public void updateName(UUID uuid, String name) {
        withRetry("updateName", () -> {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE players SET name = ? WHERE uuid = ?")) {
                ps.setString(1, name);
                ps.setString(2, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ============ async-обёртки ============

    public void updateBalanceAsync(UUID uuid, BigDecimal balance) {
        submit(() -> updateBalance(uuid, balance));
    }

    public void createAsync(UUID uuid, String name, BigDecimal startingBalance) {
        submit(() -> create(uuid, name, startingBalance));
    }

    public void updateNameAsync(UUID uuid, String name) {
        submit(() -> updateName(uuid, name));
    }

    public void insertPendingPaymentAsync(UUID recipient, UUID sender, BigDecimal amount) {
        submit(() -> insertPendingPayment(recipient, sender, amount));
    }

    public void persistTransferAsync(UUID from, BigDecimal fromBalance,
                                     UUID to, BigDecimal toBalance,
                                     BigDecimal debit, String type, BigDecimal tax) {
        submit(() -> persistTransfer(from, fromBalance, to, toBalance, debit, type, tax));
    }

    public void persistBalanceChangeAsync(UUID uuid, BigDecimal newBalance,
                                          BigDecimal amount, String type) {
        submit(() -> persistBalanceChange(uuid, newBalance, amount, type));
    }

    public void persistPendingPaymentAsync(UUID recipient, UUID sender, BigDecimal amount) {
        submit(() -> persistPendingPayment(recipient, sender, amount));
    }

    public CompletableFuture<List<Long>> deletePendingPaymentsAndReturnAsync(List<Long> ids) {
        return CompletableFuture.supplyAsync(() -> deletePendingPaymentsAndReturn(ids), executor)
                .exceptionally(ex -> {
                    logFuture("deletePendingPaymentsAndReturnAsync", ex);
                    return List.of();
                });
    }

    public CompletableFuture<Integer> deleteInactiveAccountsAsync(int days) {
        return CompletableFuture.supplyAsync(() -> deleteInactiveAccounts(days), executor)
                .exceptionally(ex -> {
                    logFuture("deleteInactiveAccountsAsync", ex);
                    return 0;
                });
    }

    public CompletableFuture<Integer> deleteOrphanedPendingPaymentsAsync(int maxAgeDays) {
        return CompletableFuture.supplyAsync(() -> deleteOrphanedPendingPayments(maxAgeDays), executor)
                .exceptionally(ex -> {
                    logFuture("deleteOrphanedPendingPaymentsAsync", ex);
                    return 0;
                });
    }

    private void submit(Runnable task) {
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ex) {
            long total = droppedTasks.incrementAndGet();
            plugin.getLogger().warning(
                    "economy-db очередь переполнена, задача отброшена (всего отброшено: " + total + "). "
                            + "Проверьте интервал autoSave и нагрузку на БД.");
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

    // ============ Чтение (sync) ============

    public String getNameByUuid(UUID uuid) {
        try {
            return withRetry("getNameByUuid", () -> {
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
                }
                return "Unknown";
            });
        } catch (RuntimeException e) {
            log("getNameByUuid", e);
            return "Unknown";
        }
    }

    public List<TopEntry> getTopBalances(int limit) {
        try {
            return withRetry("getTopBalances", () -> {
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
                }
                return List.copyOf(result);
            });
        } catch (RuntimeException e) {
            log("getTopBalances", e);
            return List.of();
        }
    }

    // ============ pending_payments ============

    public void insertPendingPayment(UUID recipient, UUID sender, BigDecimal amount) {
        withRetry("insertPendingPayment", () -> {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT INTO pending_payments (recipient_uuid, sender_uuid, amount) VALUES (?, ?, ?)")) {
                ps.setString(1, recipient.toString());
                ps.setString(2, sender.toString());
                ps.setBigDecimal(3, amount);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public List<PendingPayment> getPendingPayments(UUID recipient) {
        try {
            return withRetry("getPendingPayments", () -> {
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
                }
                return List.copyOf(result);
            });
        } catch (RuntimeException e) {
            log("getPendingPayments", e);
            return List.of();
        }
    }

    public void deletePendingPayment(long id) {
        withRetry("deletePendingPayment", () -> {
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "DELETE FROM pending_payments WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public List<Long> deletePendingPaymentsAndReturn(List<Long> ids) {
        if (ids.isEmpty()) return List.of();
        try {
            return withRetry("deletePendingPaymentsAndReturn", () -> {
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
                }
            });
        } catch (RuntimeException e) {
            log("deletePendingPaymentsAndReturn", e);
            return List.of();
        }
    }

    public int deleteOrphanedPendingPayments(int maxAgeDays) {
        int safeDays = Math.max(1, maxAgeDays);
        try {
            return withRetry("deleteOrphanedPendingPayments", () -> {
                String sql = "DELETE FROM pending_payments " +
                        "WHERE created_at < DATE_SUB(NOW(), INTERVAL " + safeDays + " DAY)";
                try (Connection c = db.getConnection();
                     PreparedStatement ps = c.prepareStatement(sql)) {
                    return ps.executeUpdate();
                }
            });
        } catch (RuntimeException e) {
            log("deleteOrphanedPendingPayments", e);
            return 0;
        }
    }

    // ============ Транзакции ============

    public void logTransaction(UUID from, UUID to, BigDecimal amount, String type) {
        withRetry("logTransaction", () -> {
            String sql = "INSERT INTO transactions (from_uuid, to_uuid, amount, type) VALUES (?, ?, ?, ?)";
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, from != null ? from.toString() : null);
                ps.setString(2, to != null ? to.toString() : null);
                ps.setBigDecimal(3, amount);
                ps.setString(4, type);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public void logTransactionAsync(UUID from, UUID to, BigDecimal amount, String type) {
        submit(() -> logTransaction(from, to, amount, type));
    }

    // ============ Очистка мёртвых аккаунтов ============

    public int deleteInactiveAccounts(int days) {
        int safeDays = Math.max(1, days);
        try {
            return withRetry("deleteInactiveAccounts", () -> {
                String sql = "DELETE FROM players WHERE balance = 0 " +
                        "AND updated_at < DATE_SUB(NOW(), INTERVAL " + safeDays + " DAY)";
                try (Connection c = db.getConnection();
                     PreparedStatement ps = c.prepareStatement(sql)) {
                    return ps.executeUpdate();
                }
            });
        } catch (RuntimeException e) {
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
        if (droppedTasks.get() > 0) {
            plugin.getLogger().warning(
                    "PlayerRepository: за время работы отброшено задач из-за переполнения очереди: "
                            + droppedTasks.get());
        }
    }

    private void log(String method, RuntimeException e) {
        plugin.getLogger().log(Level.WARNING,
                "PlayerRepository.%s(): %s".formatted(method, e.getMessage()), e);
    }

    private void logFuture(String method, Throwable ex) {
        Throwable cause = (ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null)
                ? ex.getCause() : ex;
        plugin.getLogger().log(Level.WARNING,
                "PlayerRepository.%s() async failed: %s".formatted(method, cause.getMessage()), cause);
    }

    @FunctionalInterface
    private interface ConnectionAction {
        void run(Connection c) throws SQLException;
    }

    private void upsertBalance(ConnectionAction preAction, UUID uuid, BigDecimal balance) throws SQLException {
        String sql = "INSERT INTO players (uuid, balance, updated_at) " +
                "VALUES (?, ?, CURRENT_TIMESTAMP) " +
                "ON DUPLICATE KEY UPDATE balance = VALUES(balance), updated_at = CURRENT_TIMESTAMP";
        try (Connection c = db.getConnection()) {
            boolean oldAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                preAction.run(c);
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, uuid.toString());
                    ps.setBigDecimal(2, balance);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(oldAutoCommit);
            }
        }
    }

    public record PendingPayment(long id, UUID senderUuid, BigDecimal amount) {}
    public record TopEntry(String name, BigDecimal balance) {}
}