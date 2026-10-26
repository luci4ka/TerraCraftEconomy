package ru.terracraft.economy.util;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Единый резолвер игрока по нику: онлайн → офлайн из usercache.
 * Регистронезависимый. Не делает сетевых запросов.
 */
public final class PlayerResolver {

    private PlayerResolver() {}

    /**
     * @return {@link Player} если онлайн, {@link OfflinePlayer} если в usercache,
     *         {@code null} если игрок никогда не заходил (или usercache почищен).
     */
    public static OfflinePlayer resolve(String rawName) {
        if (rawName == null) return null;
        String name = rawName.trim();
        if (name.isEmpty()) return null;

        // 1) Онлайн — точное совпадение (быстро)
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;

        // 2) Онлайн — case-insensitive
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(name)) return p;
        }

        // 3) Офлайн из usercache — case-insensitive
        for (OfflinePlayer p : Bukkit.getOfflinePlayers()) {
            if (p.getName() != null && p.getName().equalsIgnoreCase(name)) {
                return p;
            }
        }

        return null;
    }

    /** То же, но возвращает только онлайн-игрока. */
    public static Player resolveOnline(String rawName) {
        if (rawName == null) return null;
        String name = rawName.trim();
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(name)) return p;
        }
        return null;
    }
}