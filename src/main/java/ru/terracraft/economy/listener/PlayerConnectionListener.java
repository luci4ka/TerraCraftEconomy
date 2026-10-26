package ru.terracraft.economy.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.terracraft.economy.TerrEconomyPlugin;
import ru.terracraft.economy.config.PluginConfig;
import ru.terracraft.economy.db.PlayerRepository;
import ru.terracraft.economy.economy.EconomyCache;
import ru.terracraft.economy.economy.EconomyService;
import ru.terracraft.economy.util.NotificationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class PlayerConnectionListener implements Listener {

    private final TerrEconomyPlugin plugin;
    private final EconomyCache cache;
    private final PlayerRepository repo;
    private final PluginConfig config;
    private final EconomyService economyService;
    private final NotificationManager notifier;

    public PlayerConnectionListener(TerrEconomyPlugin plugin,
                                    EconomyCache cache,
                                    PlayerRepository repo,
                                    PluginConfig config,
                                    EconomyService economyService,
                                    NotificationManager notifier) {
        this.plugin = plugin;
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

        repo.getBalanceOrNullAsync(uuid).thenAccept(balance -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;

                if (balance == null) {
                    BigDecimal starting = config.economy.giveStartingBalance
                            ? config.economy.startingBalance
                            : BigDecimal.ZERO;
                    cache.createAccount(uuid, starting);
                    repo.createAsync(uuid, player.getName(), starting);
                } else {
                    cache.load(uuid, balance);
                    repo.updateNameAsync(uuid, player.getName());
                }

                deliverPendingPayments(player);
            });
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING,
                    "Не удалось загрузить аккаунт для " + player.getName(), ex);
            return null;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!cache.hasAccount(uuid)) return;
        repo.updateBalanceAsync(uuid, cache.getBalance(uuid));
    }

    private void deliverPendingPayments(Player player) {
        UUID uuid = player.getUniqueId();

        repo.getPendingPaymentsAsync(uuid).thenCompose(pending -> {
            if (pending.isEmpty()) {
                return CompletableFuture.completedFuture((Void) null);
            }

            UUID lastSenderUuid = pending.get(pending.size() - 1).senderUuid();
            List<Long> ids = pending.stream().map(PlayerRepository.PendingPayment::id).toList();

            // Удаление — через репозиторный executor (не commonPool).
            // Проверку isOnline — в main, чтобы не гонять её в async-потоке.
            return repo.getNameByUuidAsync(lastSenderUuid).thenCompose(lastSender ->
                    repo.deletePendingPaymentsAndReturnAsync(ids).thenAccept(deleted -> {
                        if (deleted.isEmpty()) return;

                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline()) {
                                // Игрок вышел между delete и зачислением.
                                // Зачисляем в кэш всё равно — потерять нельзя,
                                // onQuit/flushAll сохранит balance в БД.
                                BigDecimal total = BigDecimal.ZERO;
                                int count = 0;
                                for (var p : pending) {
                                    if (!deleted.contains(p.id())) continue;
                                    economyService.deposit(uuid, p.amount());
                                    total = total.add(p.amount());
                                    count++;
                                }
                                if (count > 0) {
                                    plugin.getLogger().info(
                                            "Офлайн-платежи для " + uuid + " зачислены в кэш, но игрок уже вышел"
                                                    + " (count=" + count + ", total=" + total.toPlainString() + ")");
                                }
                                return;
                            }

                            BigDecimal total = BigDecimal.ZERO;
                            int count = 0;
                            for (var p : pending) {
                                if (!deleted.contains(p.id())) continue;
                                economyService.deposit(uuid, p.amount());
                                total = total.add(p.amount());
                                count++;
                            }
                            if (count > 0) {
                                notifier.offlinePaymentDelivered(player, lastSender, total, count, total);
                            }
                        });
                    })
            );
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING,
                    "Не удалось доставить офлайн-платежи для " + player.getName(), ex);
            return null;
        });
    }
}