package ru.terracraft.economy.util;

import ru.terracraft.economy.config.PluginConfig;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Простой кулдаун-менеджер: {@code Map<UUID, Map<String, Long>>} (expireAt).
 * <p>Проверка — O(1), запись — O(1). Периодическая чистка не нужна:
 * протухшие записи удаляются лениво при следующем {@link #tryAcquire}.
 */
public class CooldownManager {

    private final PluginConfig config;
    private final Map<UUID, Map<String, Long>> cooldowns = new ConcurrentHashMap<>();

    public CooldownManager(PluginConfig config) {
        this.config = config;
    }

    /**
     * Пытается «захватить» действие. Возвращает {@code true}, если кулдаун
     * прошёл (или отключён), иначе {@code false}.
     *
     * @param action ключ действия (например, {@code "pay"}, {@code "balance"})
     * @param cooldownMs длительность кулдауна в мс; {@code <= 0} — без ограничения
     */
    public boolean tryAcquire(UUID uuid, String action, long cooldownMs) {
        if (cooldownMs <= 0) return true;

        long now = System.currentTimeMillis();
        Map<String, Long> playerMap = cooldowns.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());

        Long current = playerMap.get(action);
        if (current != null && current > now) {
            return false;
        }
        playerMap.put(action, now + cooldownMs);
        return true;
    }

    /** Оставшееся время кулдауна в мс (0 — свободно). */
    public long remaining(UUID uuid, String action) {
        Map<String, Long> playerMap = cooldowns.get(uuid);
        if (playerMap == null) return 0L;
        Long expireAt = playerMap.get(action);
        if (expireAt == null) return 0L;
        long now = System.currentTimeMillis();
        return Math.max(0L, expireAt - now);
    }

    /** Сброс кулдаунов игрока (например, при выходе). */
    public void clear(UUID uuid) {
        cooldowns.remove(uuid);
    }

    /** Полный сброс (при reload). */
    public void clearAll() {
        cooldowns.clear();
    }
}