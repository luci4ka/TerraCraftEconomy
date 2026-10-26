package ru.terracraft.economy.economy;

import ru.terracraft.economy.config.PluginConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class EconomyCache {

    private static final int STRIPES = 64;

    private final PluginConfig config;
    private final Map<UUID, BigDecimal> balances = new ConcurrentHashMap<>();
    private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

    public EconomyCache(PluginConfig config) {
        this.config = config;
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /** Два лока (по одному на каждый UUID), всегда в порядке возрастания индекса — без дедлока. */
    private ReentrantLock[] lockPair(UUID a, UUID b) {
        int ia = stripe(a);
        int ib = stripe(b);
        if (ia == ib) {
            return new ReentrantLock[]{ locks[ia] };
        }
        return ia < ib
                ? new ReentrantLock[]{ locks[ia], locks[ib] }
                : new ReentrantLock[]{ locks[ib], locks[ia] };
    }

    private int stripe(UUID uuid) {
        return Math.floorMod(uuid.hashCode(), STRIPES);
    }

    public boolean hasAccount(UUID uuid) {
        return balances.containsKey(uuid);
    }

    public BigDecimal getBalance(UUID uuid) {
        return balances.getOrDefault(uuid, BigDecimal.ZERO);
    }

    public void createAccount(UUID uuid) {
        var starting = config.economy.giveStartingBalance
                ? config.economy.startingBalance
                : BigDecimal.ZERO;
        createAccount(uuid, starting);
    }

    public void createAccount(UUID uuid, BigDecimal starting) {
        balances.putIfAbsent(uuid, scale(starting));
    }

    public void load(UUID uuid, BigDecimal balance) {
        balances.put(uuid, scale(balance));
    }

    public Optional<BigDecimal> withdrawIfEnough(UUID uuid, BigDecimal amount) {
        var lock = locks[stripe(uuid)];
        lock.lock();
        try {
            var current = balances.getOrDefault(uuid, BigDecimal.ZERO);
            if (current.compareTo(amount) < 0) {
                return Optional.empty();
            }
            var newBalance = scale(current.subtract(amount));
            balances.put(uuid, newBalance);
            return Optional.of(newBalance);
        } finally {
            lock.unlock();
        }
    }

    public DepositResult depositCapped(UUID uuid, BigDecimal amount) {
        var lock = locks[stripe(uuid)];
        lock.lock();
        try {
            var current = balances.getOrDefault(uuid, BigDecimal.ZERO);
            var raw = scale(current.add(amount));
            var max = scale(config.economy.maxBalance);

            BigDecimal newBalance;
            BigDecimal credited;
            if (raw.compareTo(max) > 0) {
                newBalance = max;
                credited = scale(max.subtract(current));
                if (credited.signum() < 0) {
                    credited = BigDecimal.ZERO;
                }
            } else {
                newBalance = raw;
                credited = scale(amount);
            }
            balances.put(uuid, newBalance);
            var overflow = scale(amount.subtract(credited));
            return new DepositResult(newBalance, credited, overflow);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Атомарный перевод: списание с {@code from} и зачисление {@code to}
     * под парой стрип-локов. Если зачисление упёрлось в maxBalance —
     * операция откатывается, возвращается failure.
     */
    public TransferResult transferIfPossible(UUID from, UUID to,
                                             BigDecimal debit, BigDecimal credit) {
        if (from.equals(to)) {
            return TransferResult.sameAccount();
        }

        var pair = lockPair(from, to);
        for (var l : pair) l.lock();
        try {
            var fromCurrent = balances.getOrDefault(from, BigDecimal.ZERO);
            if (fromCurrent.compareTo(debit) < 0) {
                return TransferResult.insufficient(fromCurrent);
            }

            var toCurrent = balances.getOrDefault(to, BigDecimal.ZERO);
            var max = scale(config.economy.maxBalance);
            var toRaw = scale(toCurrent.add(credit));

            if (toRaw.compareTo(max) > 0) {
                return TransferResult.overflow(toCurrent);
            }

            var newFrom = scale(fromCurrent.subtract(debit));
            var newTo = scale(toRaw);
            balances.put(from, newFrom);
            balances.put(to, newTo);
            return TransferResult.success(newFrom, newTo);
        } finally {
            for (int i = pair.length - 1; i >= 0; i--) pair[i].unlock();
        }
    }

    public BigDecimal set(UUID uuid, BigDecimal amount) {
        var lock = locks[stripe(uuid)];
        lock.lock();
        try {
            var capped = scale(amount);
            var max = scale(config.economy.maxBalance);
            if (capped.compareTo(max) > 0) capped = max;
            if (capped.signum() < 0) capped = BigDecimal.ZERO;
            balances.put(uuid, capped);
            return capped;
        } finally {
            lock.unlock();
        }
    }

    public Map<UUID, BigDecimal> snapshot() {
        return Map.copyOf(balances);
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(config.economy.fractionalDigits, RoundingMode.HALF_UP);
    }

    public record DepositResult(BigDecimal newBalance, BigDecimal credited, BigDecimal overflow) {}

    /** Результат атомарного transfer. */
    public sealed interface TransferResult
            permits TransferResult.Success, TransferResult.Insufficient,
            TransferResult.Overflow, TransferResult.SameAccount {

        record Success(BigDecimal newFromBalance, BigDecimal newToBalance) implements TransferResult {}
        record Insufficient(BigDecimal currentFromBalance) implements TransferResult {}
        record Overflow(BigDecimal currentToBalance) implements TransferResult {}
        record SameAccount() implements TransferResult {}

        static TransferResult success(BigDecimal from, BigDecimal to) { return new Success(from, to); }
        static TransferResult insufficient(BigDecimal current) { return new Insufficient(current); }
        static TransferResult overflow(BigDecimal current) { return new Overflow(current); }
        static TransferResult sameAccount() { return new SameAccount(); }
    }
}