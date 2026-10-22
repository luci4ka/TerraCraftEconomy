package ru.terracraft.economy.economy;

import ru.terracraft.economy.config.PluginConfig;
import ru.terracraft.economy.db.PlayerRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

public class EconomyService {

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

    /**
     * Начисление. Если упёрлись в maxBalance — «излишек» не зачисляется.
     * Возвращает новый баланс.
     */
    public BigDecimal deposit(UUID uuid, BigDecimal amount) {
        return depositAndReport(uuid, amount).newBalance();
    }

    /**
     * Начисление с отчётом: возвращает, сколько реально зачислено (credited)
     * и сколько не поместилось (overflow). Нужно Vault-провайдеру, чтобы
     * корректно отдавать EconomyResponse.SUCCESS/FAILURE.
     */
    public EconomyCache.DepositResult depositAndReport(UUID uuid, BigDecimal amount) {
        var scaled = scale(amount);
        var result = cache.depositCapped(uuid, scaled);
        repo.updateBalanceAsync(uuid, result.newBalance());
        repo.logTransactionAsync(null, uuid, result.credited(), "DEPOSIT");
        if (result.overflow().signum() > 0) {
            // Логируем «потерянный» излишек — иначе он исчезнет молча.
            repo.logTransactionAsync(null, uuid, result.overflow(), "DEPOSIT_OVERFLOW");
        }
        return result;
    }

    /** Списание. Возвращает null, если недостаточно средств. */
    public BigDecimal withdraw(UUID uuid, BigDecimal amount) {
        var scaled = scale(amount);
        var newBalance = cache.withdrawIfEnough(uuid, scaled).orElse(null);
        if (newBalance == null) {
            return null;
        }
        repo.updateBalanceAsync(uuid, newBalance);
        repo.logTransactionAsync(uuid, null, scaled, "WITHDRAW");
        return newBalance;
    }

    /** Установка баланса напрямую (админ). Валидирует maxBalance. */
    public BigDecimal set(UUID uuid, BigDecimal amount) {
        var scaled = scale(amount);
        var applied = cache.set(uuid, scaled);
        repo.updateBalanceAsync(uuid, applied);
        repo.logTransactionAsync(null, uuid, applied, "SET");
        return applied;
    }

    /** Перевод между игроками без налога. */
    public boolean transfer(UUID from, UUID to, BigDecimal amount) {
        return doTransfer(from, to, amount, scale(amount), "TRANSFER");
    }

    /** Перевод с налогом: отправитель теряет amount, получатель получает (amount − tax). */
    public boolean transferWithTax(UUID from, UUID to, BigDecimal amount, BigDecimal tax) {
        var scaled = scale(amount);
        var net = scale(amount.subtract(tax));
        if (net.signum() < 0) {
            return false;
        }
        boolean ok = doTransfer(from, to, scaled, net, "PAY");
        if (ok && tax.signum() > 0) {
            // Фиксируем налог в аудите — иначе сумма WITHDRAW (-amount)
            // и DEPOSIT (+net) не сойдётся, и налог «исчезнет» из истории.
            repo.logTransactionAsync(from, null, scale(tax), "TAX");
        }
        return ok;
    }

    private boolean doTransfer(UUID from, UUID to, BigDecimal debit, BigDecimal credit, String type) {
        if (from.equals(to)) {
            return false;
        }

        BigDecimal newFrom;
        EconomyCache.DepositResult depositResult;

        synchronized (cache) {
            var withdrawn = cache.withdrawIfEnough(from, debit);
            if (withdrawn.isEmpty()) {
                return false;
            }
            newFrom = withdrawn.get();

            depositResult = cache.depositCapped(to, credit);

            if (depositResult.overflow().signum() > 0) {
                cache.load(from, newFrom.add(debit));
                return false;
            }
        }

        repo.updateBalanceAsync(from, newFrom);
        repo.updateBalanceAsync(to, depositResult.newBalance());
        repo.logTransactionAsync(from, to, debit, type);

        return true;
    }

    public boolean hasEnough(UUID uuid, BigDecimal amount) {
        return cache.getBalance(uuid).compareTo(scale(amount)) >= 0;
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(config.economy.fractionalDigits, RoundingMode.HALF_UP);
    }

    public void queueOfflinePayment(UUID recipient, UUID sender, BigDecimal amount) {
        repo.insertPendingPayment(recipient, sender, amount);
    }
}