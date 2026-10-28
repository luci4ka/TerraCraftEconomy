package ru.terracraft.economy.util;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Единый резолвер игрока по нику: онлайн → usercache (Paper, in-memory).
 * Регистронезависимый. <b>Не делает сетевых запросов</b> и не перебирает
 * {@code Bukkit.getOfflinePlayers()} (это O(n) и парсинг usercache.json).
 *
 * <p>Порядок:
 * <ol>
 *   <li>{@link Bukkit#getPlayerExact(String)} — онлайн, точное совпадение, O(1).</li>
 *   <li>{@link Bukkit#getOfflinePlayerIfCached(String)} — Paper-кэш в памяти
 *       (онлайн + когда-либо заходившие). Возвращает {@code null}, если игрок
 *       никогда не заходил — это корректный сигнал для
 *       {@code GENERAL_PLAYER_NEVER_JOINED}.</li>
 *   <li>Локальный TTL-кэш {@link #CACHE} — на случай, если Paper-кэш пуст,
 *       но игрок уже резолвился недавно (например, после перезагрузки сервера
 *       до первого join).</li>
 * </ol>
 *
 * <p><b>Не используем</b> {@code Bukkit.getOfflinePlayer(String)}: он делает
 * блокирующий HTTP-запрос к Mojang на главном потоке.
 */
public final class PlayerResolver {

    /** TTL для локального кэша резолвов. */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    /** Ограничение размера кэша — защита от роста на больших серверах. */
    private static final int CACHE_MAX = 4096;

    private static final Map<String, CachedPlayer> CACHE = new ConcurrentHashMap<>();

    private PlayerResolver() {}

    /**
     * @return {@link Player} если онлайн, {@link OfflinePlayer} если в usercache
     *         (или в локальном кэше), {@code null} если игрок никогда не заходил
     *         (или usercache почищен).
     */
    public static OfflinePlayer resolve(String rawName) {
        if (rawName == null) return null;
        String name = rawName.trim();
        if (name.isEmpty()) return null;

        // 1) Онлайн — точное совпадение (O(1), без аллокаций).
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;

        // 2) Paper-кэш (in-memory, без HTTP).
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null) {
            remember(name, cached);
            return cached;
        }

        // 3) Локальный TTL-кэш.
        String key = name.toLowerCase();
        CachedPlayer local = CACHE.get(key);
        long now = System.currentTimeMillis();
        if (local != null && local.expiresAt > now) {
            OfflinePlayer p = Bukkit.getOfflinePlayerIfCached(local.name);
            // Мог выйти из usercache между вызовами — тогда null.
            return p;
        }
        if (local != null) {
            CACHE.remove(key, local);
        }

        // Не найден — не делаем HTTP, возвращаем null.
        return null;
    }

    /** То же, но возвращает только онлайн-игрока. */
    public static Player resolveOnline(String rawName) {
        if (rawName == null) return null;
        String name = rawName.trim();
        if (name.isEmpty()) return null;

        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) return exact;

        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        return (cached instanceof Player p) ? p : null;
    }

    /** Сброс кэша (например, при reload). */
    public static void clearCache() {
        CACHE.clear();
    }

    private static void remember(String name, OfflinePlayer player) {
        if (player.getName() == null) return;
        if (CACHE.size() >= CACHE_MAX) {
            // Простая защита от роста: чистим протухшие, если не помогло — не кладём.
            long now = System.currentTimeMillis();
            CACHE.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
            if (CACHE.size() >= CACHE_MAX) return;
        }
        CACHE.put(player.getName().toLowerCase(),
                new CachedPlayer(player.getName(), System.currentTimeMillis() + CACHE_TTL_MS));
    }

    private record CachedPlayer(String name, long expiresAt) {}
}