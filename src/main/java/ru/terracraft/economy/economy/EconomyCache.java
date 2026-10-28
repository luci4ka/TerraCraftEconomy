package ru.terracraft.economy.economy;

import ru.terracraft.economy.config.PluginConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

public class EconomyCache {

    private static final int STRIPES = 64;

    /** Жёсткий лимит записей — защита от роста на больших серверах. */
    private static final int MAX_ENTRIES = 50_000;

    /** Сколько удалять при переполнении (10% от превышения). */
    private static final double EVICT_RATIO = 0.10;

    private final PluginConfig config;
    private final Map<UUID, Entry> balances = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

    /** Флаг «переполнено» — выставляется в put, сбрасывается в ensureCapacity/evictIdle. */
    private final AtomicBoolean overCapacity = new AtomicBoolean(false);

    public EconomyCache(PluginConfig config) {
        this.config = config;
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

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

    // ============ Чтение ============

    public boolean hasAccount(UUID uuid) {
        return balances.containsKey(uuid);
    }

    public BigDecimal getBalance(UUID uuid) {
        Entry e = balances.get(uuid);
        if (e == null) return BigDecimal.ZERO;
        e.lastAccess = System.currentTimeMillis();
        return e.value;
    }

    // ============ Создание / загрузка ============

    public void createAccount(UUID uuid) {
        var starting = config.economy.giveStartingBalance
                ? config.economy.startingBalance
                : BigDecimal.ZERO;
        createAccount(uuid, starting);
    }

    public void createAccount(UUID uuid, BigDecimal starting) {
        balances.putIfAbsent(uuid, new Entry(scale(starting)));
        dirty.add(uuid);
        ensureCapacity();
    }

    public void load(UUID uuid, BigDecimal balance) {
        balances.put(uuid, new Entry(scale(balance)));
        dirty.add(uuid);
        ensureCapacity();
    }

    // ============ Изменение баланса ============

    public Optional<BigDecimal> withdrawIfEnough(UUID uuid, BigDecimal amount) {
        var lock = locks[stripe(uuid)];
        lock.lock();
        try {
            var e = balances.get(uuid);
            var current = e == null ? BigDecimal.ZERO : e.value;
            if (current.compareTo(amount) < 0) {
                return Optional.empty();
            }
            var newBalance = scale(current.subtract(amount));
            put(uuid, newBalance);
            return Optional.of(newBalance);
        } finally {
            lock.unlock();
        }
    }

    public DepositResult depositCapped(UUID uuid, BigDecimal amount) {
        var lock = locks[stripe(uuid)];
        lock.lock();
        try {
            var e = balances.get(uuid);
            var current = e == null ? BigDecimal.ZERO : e.value;
            var raw = scale(current.add(amount));
            var max = scale(config.economy.maxBalance);

            BigDecimal newBalance;
            BigDecimal credited;
            if (raw.compareTo(max) > 0) {
                newBalance = max;
                credited = scale(max.subtract(current));
                if (credited.signum() < 0) credited = BigDecimal.ZERO;
            } else {
                newBalance = raw;
                credited = scale(amount);
            }
            put(uuid, newBalance);
            var overflow = scale(amount.subtract(credited));
            return new DepositResult(newBalance, credited, overflow);
        } finally {
            lock.unlock();
        }
    }

    public boolean depositAllOrNothing(UUID uuid, BigDecimal amount) {
        var lock = locks[stripe(uuid)];
        lock.lock();
        try {
            var e = balances.get(uuid);
            var current = e == null ? BigDecimal.ZERO : e.value;
            var max = scale(config.economy.maxBalance);
            var raw = scale(current.add(amount));
            if (raw.compareTo(max) > 0) {
                return false;
            }
            put(uuid, raw);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public TransferResult transferIfPossible(UUID from, UUID to,
                                             BigDecimal debit, BigDecimal credit) {
        if (from.equals(to)) {
            return TransferResult.sameAccount();
        }

        var pair = lockPair(from, to);
        for (var l : pair) l.lock();
        try {
            var fromEntry = balances.get(from);
            var fromCurrent = fromEntry == null ? BigDecimal.ZERO : fromEntry.value;
            if (fromCurrent.compareTo(debit) < 0) {
                return TransferResult.insufficient(fromCurrent);
            }

            var toEntry = balances.get(to);
            var toCurrent = toEntry == null ? BigDecimal.ZERO : toEntry.value;
            var max = scale(config.economy.maxBalance);
            var toRaw = scale(toCurrent.add(credit));

            if (toRaw.compareTo(max) > 0) {
                return TransferResult.overflow(toCurrent);
            }

            var newFrom = scale(fromCurrent.subtract(debit));
            var newTo = scale(toRaw);
            put(from, newFrom);
            put(to, newTo);
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
            put(uuid, capped);
            return capped;
        } finally {
            lock.unlock();
        }
    }

    // ============ Снапшот / dirty / эвикция ============

    /** Полный снимок — для {@code onDisable} и гарантированного flush. */
    public Map<UUID, BigDecimal> snapshot() {
        var out = new ConcurrentHashMap<UUID, BigDecimal>(balances.size());
        for (var e : balances.entrySet()) {
            out.put(e.getKey(), e.getValue().value);
        }
        return Map.copyOf(out);
    }

    /**
     * Инкрементальный снимок: только изменённые. <b>НЕ очищает</b> dirty-set —
     * вызывающий должен вызвать {@link #clearDirty(Collection)} после успешного
     * flush, иначе при сбое БД записи будут потеряны.
     */
    public Map<UUID, BigDecimal> snapshotDirty() {
        if (dirty.isEmpty()) return Map.of();
        var out = new ConcurrentHashMap<UUID, BigDecimal>(dirty.size());
        for (UUID uuid : dirty) {
            Entry e = balances.get(uuid);
            if (e != null) out.put(uuid, e.value);
        }
        return Map.copyOf(out);
    }

    /** Удаляет из dirty-set только успешно сохранённые UUID. */
    public void clearDirty(Collection<UUID> uuids) {
        if (uuids == null || uuids.isEmpty()) return;
        dirty.removeAll(uuids);
    }

    /**
     * Удаляет из кэша записи, к которым не обращались дольше {@code ttlMs},
     * при условии {@code keepAlive.test(uuid) == false}. Онлайн-игроков
     * не трогает (keepAlive обычно {@code Bukkit::getPlayer != null}).
     * <p><b>Вызывать только после flushAll</b>, иначе потеряете несохранённый баланс.
     *
     * @return количество вытесненных записей.
     */
    public int evictIdle(long ttlMs, Predicate<UUID> keepAlive) {
        long cutoff = System.currentTimeMillis() - ttlMs;
        int removed = 0;
        for (var it = balances.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue().lastAccess > cutoff) continue;
            if (keepAlive != null && keepAlive.test(e.getKey())) continue;
            it.remove();
            removed++;
        }
        if (removed > 0) {
            overCapacity.set(false);
        }
        return removed;
    }

    // ============ Внутреннее ============

    private void put(UUID uuid, BigDecimal value) {
        Entry e = balances.get(uuid);
        if (e == null) {
            balances.put(uuid, new Entry(value));
        } else {
            e.value = value;
            e.lastAccess = System.currentTimeMillis();
        }
        dirty.add(uuid);
        ensureCapacity();
    }

    /**
     * Fallback-эвикция при переполнении. Вызывается из {@link #put} при
     * {@code size > MAX_ENTRIES}. Не вытесняет онлайн (нет Bukkit-контекста),
     * но:
     * <ul>
     *   <li>O(n) — один проход + PriorityQueue min-heap, <b>не</b> полная сортировка;</li>
     *   <li>удаляет {@code 10%} самых старых (по {@code lastAccess}).</li>
     * </ul>
     * Точную эвикцию с {@code keepAlive} делает {@link #evictIdle} из async-таска.
     */
    private void ensureCapacity() {
        if (!overCapacity.compareAndSet(false, true)) {
            if (balances.size() <= MAX_ENTRIES) {
                overCapacity.set(false);
                return;
            }
        }
        if (balances.size() <= MAX_ENTRIES) {
            overCapacity.set(false);
            return;
        }

        int toRemove = Math.max(1, (int) ((balances.size() - MAX_ENTRIES) * EVICT_RATIO) + 1);
        var pq = new PriorityQueue<Map.Entry<UUID, Entry>>(
                Comparator.comparingLong(e -> e.getValue().lastAccess));
        pq.addAll(balances.entrySet());
        List<UUID> toEvict = new ArrayList<>(toRemove);
        for (int i = 0; i < toRemove && !pq.isEmpty(); i++) {
            toEvict.add(pq.poll().getKey());
        }
        for (UUID uuid : toEvict) {
            balances.remove(uuid);
            dirty.remove(uuid);
        }
        if (balances.size() > MAX_ENTRIES) {
            return;
        }
        overCapacity.set(false);
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(config.economy.fractionalDigits, RoundingMode.HALF_UP);
    }

    private static final class Entry {
        volatile BigDecimal value;
        volatile long lastAccess;
        Entry(BigDecimal value) {
            this.value = value;
            this.lastAccess = System.currentTimeMillis();
        }
    }

    public record DepositResult(BigDecimal newBalance, BigDecimal credited, BigDecimal overflow) {}

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