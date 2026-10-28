package ru.terracraft.economy.economy;

import ru.terracraft.economy.config.PluginConfig;
import ru.terracraft.economy.db.PlayerRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public class EconomyService {

    public enum TransferOutcome { SUCCESS, INSUFFICIENT, TARGET_OVERFLOW, SAME_ACCOUNT }

    private final EconomyCache cache;
    private final PlayerRepository repo;
    private final PluginConfig config;

    public EconomyService(EconomyCache cache, PlayerRepository repo, PluginConfig config) {
        this.cache = cache;
        this.repo = repo;
        this.config = config;
    }

    public boolean hasAccount(UUID uuid) {
        return cache.hasAccount(uuid);
    }

    public BigDecimal getBalance(UUID uuid) {
        return cache.getBalance(uuid);
    }

    public void createAccount(UUID uuid, String name) {
        var starting = config.economy.giveStartingBalance
                ? config.economy.startingBalance
                : BigDecimal.ZERO;
        cache.createAccount(uuid, starting);
        repo.createAsync(uuid, name, starting);
    }

    public void loadAccount(UUID uuid, BigDecimal balance) {
        cache.load(uuid, balance);
    }

    public BigDecimal deposit(UUID uuid, BigDecimal amount) {
        return depositAndReport(uuid, amount).newBalance();
    }

    /**
     * Зачисление с капом. Атомарно: баланс + лог в одной транзакции БД.
     * <p>Используется в {@code /eco give} — частичное зачисление с уведомлением
     * (overflow логируется отдельной записью; редкий кейс, порядок в аудите
     * не критичен).
     */
    public EconomyCache.DepositResult depositAndReport(UUID uuid, BigDecimal amount) {
        var scaled = scale(amount);
        var result = cache.depositCapped(uuid, scaled);

        repo.persistBalanceChangeAsync(uuid, result.newBalance(), result.credited(), "DEPOSIT");
        if (result.overflow().signum() > 0) {
            repo.logTransactionAsync(null, uuid, result.overflow(), "DEPOSIT_OVERFLOW");
        }
        return result;
    }

    /**
     * Атомарный депозит «всё или ничего». При превышении maxBalance
     * возвращает {@code false} и не меняет баланс.
     */
    public boolean depositAllOrNothing(UUID uuid, BigDecimal amount) {
        var scaled = scale(amount);
        if (!cache.depositAllOrNothing(uuid, scaled)) {
            return false;
        }
        repo.persistBalanceChangeAsync(uuid, cache.getBalance(uuid), scaled, "DEPOSIT");
        return true;
    }

    /**
     * Списывает сумму. Возвращает новый баланс, либо {@code null}, если
     * списание невозможно (недостаточно средств или сумма {@code <= 0}).
     */
    public BigDecimal withdraw(UUID uuid, BigDecimal amount) {
        var scaled = scale(amount);
        if (scaled.signum() <= 0) {
            return null;
        }
        var newBalance = cache.withdrawIfEnough(uuid, scaled).orElse(null);
        if (newBalance == null) {
            return null;
        }
        repo.persistBalanceChangeAsync(uuid, newBalance, scaled, "WITHDRAW");
        return newBalance;
    }

    public BigDecimal set(UUID uuid, BigDecimal amount) {
        var scaled = scale(amount);
        var applied = cache.set(uuid, scaled);
        repo.persistBalanceChangeAsync(uuid, applied, applied, "SET");
        return applied;
    }

    public boolean transfer(UUID from, UUID to, BigDecimal amount) {
        return doTransfer(from, to, scale(amount), scale(amount), "TRANSFER", BigDecimal.ZERO)
                == TransferOutcome.SUCCESS;
    }

    public boolean transferWithTax(UUID from, UUID to, BigDecimal amount, BigDecimal tax) {
        return transferWithTaxDetailed(from, to, amount, tax) == TransferOutcome.SUCCESS;
    }

    public TransferOutcome transferWithTaxDetailed(UUID from, UUID to,
                                                   BigDecimal amount, BigDecimal tax) {
        var scaled = scale(amount);
        var net = scale(amount.subtract(tax));
        if (net.signum() < 0) {
            return TransferOutcome.INSUFFICIENT;
        }
        return doTransfer(from, to, scaled, net, "PAY", scale(tax));
    }

    private TransferOutcome doTransfer(UUID from, UUID to, BigDecimal debit, BigDecimal credit,
                                       String type, BigDecimal tax) {
        if (from.equals(to)) {
            return TransferOutcome.SAME_ACCOUNT;
        }

        var result = cache.transferIfPossible(from, to, debit, credit);
        if (result instanceof EconomyCache.TransferResult.Insufficient) {
            return TransferOutcome.INSUFFICIENT;
        }
        if (result instanceof EconomyCache.TransferResult.Overflow) {
            return TransferOutcome.TARGET_OVERFLOW;
        }
        if (!(result instanceof EconomyCache.TransferResult.Success success)) {
            return TransferOutcome.SAME_ACCOUNT;
        }

        repo.persistTransferAsync(
                from, success.newFromBalance(),
                to, success.newToBalance(),
                debit, type, tax);
        return TransferOutcome.SUCCESS;
    }

    public boolean hasEnough(UUID uuid, BigDecimal amount) {
        return cache.getBalance(uuid).compareTo(scale(amount)) >= 0;
    }

    /**
     * Ставит офлайн-платёж в очередь. Атомарно: INSERT pending_payments +
     * лог PAY_QUEUED в одной транзакции.
     */
    public void queueOfflinePayment(UUID recipient, UUID sender, BigDecimal amount) {
        repo.persistPendingPaymentAsync(recipient, sender, amount);
    }

    /**
     * Инкрементальный снимок для автосейва: только изменённые.
     * <b>НЕ очищает</b> dirty-set — вызывающий должен вызвать
     * {@link #clearDirty(Collection)} после успешного {@code flushAll}.
     */
    public Map<UUID, BigDecimal> snapshotDirty() {
        return cache.snapshotDirty();
    }

    /** Удаляет из dirty-set только успешно сохранённые UUID. */
    public void clearDirty(Collection<UUID> uuids) {
        cache.clearDirty(uuids);
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(config.economy.fractionalDigits, RoundingMode.HALF_UP);
    }
}