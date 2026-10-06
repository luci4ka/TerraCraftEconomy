package ru.net.terracraft.eco.economy;

import ru.net.terracraft.eco.config.PluginConfig;
import ru.net.terracraft.eco.db.PlayerRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class EconomyCache {

    private final PluginConfig config;
    private final Map<UUID, BigDecimal> balances = new ConcurrentHashMap<>();

    public EconomyCache(PluginConfig config) {
        this.config = config;
    }

    public boolean hasAccount(UUID uuid) {
        return balances.containsKey(uuid);
    }

    public BigDecimal getBalance(UUID uuid) {
        return balances.getOrDefault(uuid, BigDecimal.ZERO);
    }

    public void createAccount(UUID uuid) {
        balances.putIfAbsent(uuid, scale(config.economy.startingBalance));
    }

    public void load(UUID uuid, BigDecimal balance) {
        balances.put(uuid, scale(balance));
    }

    public synchronized BigDecimal withdraw(UUID uuid, BigDecimal amount) {
        BigDecimal current = balances.getOrDefault(uuid, BigDecimal.ZERO);
        BigDecimal newBalance = scale(current.subtract(amount));
        balances.put(uuid, newBalance);
        return newBalance;
    }

    public synchronized BigDecimal deposit(UUID uuid, BigDecimal amount) {
        BigDecimal current = balances.getOrDefault(uuid, BigDecimal.ZERO);
        BigDecimal newBalance = scale(current.add(amount));
        if (newBalance.compareTo(config.economy.maxBalance) > 0) {
            newBalance = scale(config.economy.maxBalance);
        }
        balances.put(uuid, newBalance);
        return newBalance;
    }

    public void flushAll(PlayerRepository repo) {
        balances.forEach(repo::updateBalance);
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(config.economy.fractionalDigits, RoundingMode.HALF_UP);
    }
}