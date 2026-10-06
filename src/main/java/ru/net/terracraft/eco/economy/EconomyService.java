package ru.net.terracraft.eco.economy;

import ru.net.terracraft.eco.config.PluginConfig;
import ru.net.terracraft.eco.db.PlayerRepository;

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
        BigDecimal scaled = scale(amount);
        BigDecimal newBalance = cache.deposit(uuid, scaled);
        repo.updateBalanceAsync(uuid, newBalance);
        return newBalance;
    }

    /** Списание. Возвращает null, если недостаточно средств. */
    public BigDecimal withdraw(UUID uuid, BigDecimal amount) {
        BigDecimal scaled = scale(amount);
        if (cache.getBalance(uuid).compareTo(scaled) < 0) {
            return null;
        }
        BigDecimal newBalance = cache.withdraw(uuid, scaled);
        repo.updateBalanceAsync(uuid, newBalance);
        return newBalance;
    }

    /** Установка баланса напрямую (админ). */
    public BigDecimal set(UUID uuid, BigDecimal amount) {
        BigDecimal scaled = scale(amount);
        cache.load(uuid, scaled);
        repo.updateBalanceAsync(uuid, scaled);
        return scaled;
    }

    /** Перевод между игроками без налога. */
    public boolean transfer(UUID from, UUID to, BigDecimal amount) {
        BigDecimal scaled = scale(amount);

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

        return true;
    }

    /** Перевод с налогом: отправитель теряет amount, получатель получает (amount − tax). */
    public boolean transferWithTax(UUID from, UUID to, BigDecimal amount, BigDecimal tax) {
        BigDecimal scaled = scale(amount);
        BigDecimal net = scale(amount.subtract(tax));

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