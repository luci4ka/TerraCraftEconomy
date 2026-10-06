package ru.net.terracraft.eco.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.net.terracraft.eco.config.PluginConfig;
import ru.net.terracraft.eco.db.PlayerRepository;
import ru.net.terracraft.eco.economy.EconomyCache;
import ru.net.terracraft.eco.economy.EconomyService;
import ru.net.terracraft.eco.util.NotificationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public class PlayerConnectionListener implements Listener {

    private final EconomyCache cache;
    private final PlayerRepository repo;
    private final PluginConfig config;
    private final EconomyService economyService;
    private final NotificationManager notifier;

    public PlayerConnectionListener(EconomyCache cache,
                                    PlayerRepository repo,
                                    PluginConfig config,
                                    EconomyService economyService,
                                    NotificationManager notifier) {
        this.cache = cache;
        this.repo = repo;
        this.config = config;
        this.economyService = economyService;
        this.notifier = notifier;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (!repo.exists(uuid)) {
            cache.createAccount(uuid);
            repo.createAsync(uuid, player.getName(), config.economy.startingBalance);
        } else {
            BigDecimal balance = repo.getBalance(uuid);
            cache.load(uuid, balance);
            repo.updateNameAsync(uuid, player.getName());
        }

        deliverPendingPayments(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        repo.updateBalanceAsync(uuid, cache.getBalance(uuid));
    }

    private void deliverPendingPayments(Player player) {
        UUID uuid = player.getUniqueId();
        List<PlayerRepository.PendingPayment> pending = repo.getPendingPayments(uuid);
        if (pending.isEmpty()) return;

        BigDecimal total = BigDecimal.ZERO;
        int count = 0;

        // Один раз зачисляем все платежи, собираем статистику
        for (PlayerRepository.PendingPayment p : pending) {
            economyService.deposit(uuid, p.amount());
            repo.deletePendingPayment(p.id());

            total = total.add(p.amount());
            count++;
        }

        // Показываем сводку: Title + Action Bar + чек — по одному разу
        String lastSender = repo.getNameByUuid(pending.get(pending.size() - 1).senderUuid());
        notifier.offlinePaymentDelivered(player, lastSender, total, count, total);
    }
}