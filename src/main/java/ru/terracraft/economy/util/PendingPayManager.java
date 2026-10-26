package ru.terracraft.economy.util;

import ru.terracraft.economy.config.PluginConfig;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PendingPayManager {

    private final Map<UUID, PendingPayConfirm> pending = new ConcurrentHashMap<>();
    private final PluginConfig config;

    public PendingPayManager(PluginConfig config) {
        this.config = config;
    }

    /** Создаёт новый pending, возвращает предыдущий (или null, если не было/просрочен). */
    public PendingPayConfirm createAndReturnPrevious(UUID sender, String targetName, BigDecimal amount) {
        PendingPayConfirm prev = pending.put(sender,
                new PendingPayConfirm(sender, targetName, amount, config.pay.confirmTimeout));
        if (prev == null || prev.isExpired()) return null;
        return prev;
    }

    public PendingPayConfirm consume(UUID sender) {
        PendingPayConfirm p = pending.remove(sender);
        if (p == null || p.isExpired()) return null;
        return p;
    }

    public void cleanup() {
        pending.entrySet().removeIf(e -> e.getValue().isExpired());
    }
}