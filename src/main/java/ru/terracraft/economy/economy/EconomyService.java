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
        cache.createAccount(uuid);
        repo.createAsync(uuid, name, config.economy.startingBalance);
    }

    public void loadAccount(UUID uuid, BigDecimal balance) {
        cache.load(uuid, balance);
    }

    /** Начисление. Возвращает новый баланс. */
    public BigDecimal deposit(UUID uuid, BigDecimal amount) {
        var scaled = scale(amount);
        var newBalance = cache.deposit(uuid, scaled);
        repo.updateBalanceAsync(uuid, newBalance);
        repo.logTransactionAsync(null, uuid, scaled, "DEPOSIT");
        return newBalance;
    }

    /** Списание. Возвращает null, если недостаточно средств. */
    public BigDecimal withdraw(UUID uuid, BigDecimal amount) {
        var scaled = scale(amount);
        if (cache.getBalance(uuid).compareTo(scaled) < 0) {
            return null;
        }
        var newBalance = cache.withdraw(uuid, scaled);
        repo.updateBalanceAsync(uuid, newBalance);
        repo.logTransactionAsync(uuid, null, scaled, "WITHDRAW");
        return newBalance;
    }

    /** Установка баланса напрямую (админ). */
    public BigDecimal set(UUID uuid, BigDecimal amount) {
        var scaled = scale(amount);
        cache.load(uuid, scaled);
        repo.updateBalanceAsync(uuid, scaled);
        repo.logTransactionAsync(null, uuid, scaled, "SET");
        return scaled;
    }

    /** Перевод между игроками без налога. */
    public boolean transfer(UUID from, UUID to, BigDecimal amount) {
        var scaled = scale(amount);

        if (cache.getBalance(from).compareTo(scaled) < 0) {
            return false;
        }

        BigDecimal newFrom;
        BigDecimal newTo;

        synchronized (cache) {
            newFrom = cache.withdraw(from, scaled);
            newTo = cache.deposit(to, scaled);
        }

        repo.updateBalanceAsync(from, newFrom);
        repo.updateBalanceAsync(to, newTo);
        repo.logTransactionAsync(from, to, scaled, "TRANSFER");

        return true;
    }

    /** Перевод с налогом: отправитель теряет amount, получатель получает (amount − tax). */
    public boolean transferWithTax(UUID from, UUID to, BigDecimal amount, BigDecimal tax) {
        var scaled = scale(amount);
        var net = scale(amount.subtract(tax));

        BigDecimal newFrom;
        BigDecimal newTo;

        synchronized (cache) {
            if (cache.getBalance(from).compareTo(scaled) < 0) {
                return false;
            }
            newFrom = cache.withdraw(from, scaled);
            newTo = cache.deposit(to, net);
        }

        repo.updateBalanceAsync(from, newFrom);
        repo.updateBalanceAsync(to, newTo);
        repo.logTransactionAsync(from, to, scaled, "PAY");

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
        // Офлайн-платёж ещё не доставлен, но факт списания у отправителя
        // уже зафиксирован в withdraw() выше. Отдельную запись не создаём,
        // чтобы не дублировать: когда платёж доставится, сработает deposit()
        // с типом DEPOSIT.
    }
}