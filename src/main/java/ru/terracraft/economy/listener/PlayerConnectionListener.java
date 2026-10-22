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

        repo.existsAsync(uuid).thenCompose(exists -> {
            if (!exists) {
                return CompletableFuture.completedFuture((BigDecimal) null);
            }
            return repo.getBalanceAsync(uuid).thenApply(balance -> balance);
        }).thenAccept(balance -> {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;

                if (balance == null) {
                    cache.createAccount(uuid);
                    repo.createAsync(uuid, player.getName(), config.economy.startingBalance);
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

        // Если аккаунт ещё не успел загрузиться (быстрый rejoin до завершения
        // existsAsync/getBalanceAsync), сохранять нечего — иначе затрём реальный
        // баланс нулём.
        if (!cache.hasAccount(uuid)) {
            return;
        }
        repo.updateBalanceAsync(uuid, cache.getBalance(uuid));
    }

    private void deliverPendingPayments(Player player) {
        UUID uuid = player.getUniqueId();

        repo.getPendingPaymentsAsync(uuid).thenCompose(pending -> {
            if (pending.isEmpty()) {
                return CompletableFuture.completedFuture((Void) null);
            }

            BigDecimal total = BigDecimal.ZERO;
            for (PlayerRepository.PendingPayment p : pending) {
                total = total.add(p.amount());
            }
            final BigDecimal finalTotal = total;

            UUID lastSenderUuid = pending.get(pending.size() - 1).senderUuid();
            return repo.getNameByUuidAsync(lastSenderUuid).thenAccept(lastSender -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;

                    // 1) Сначала зачисляем в кэш (синхронно, в главном потоке).
                    //    Если сервер упадёт между зачислением и удалением,
                    //    при следующем входе платёж зачислится повторно — это
                    //    менее опасно, чем потерять платёж (дубль можно
                    //    отследить по transactions и откатить вручную).
                    for (PlayerRepository.PendingPayment p : pending) {
                        economyService.deposit(uuid, p.amount());
                    }

                    // 2) Только после успешного зачисления удаляем записи.
                    for (PlayerRepository.PendingPayment p : pending) {
                        repo.deletePendingPaymentAsync(p.id());
                    }

                    notifier.offlinePaymentDelivered(player, lastSender,
                            finalTotal, pending.size(), finalTotal);
                });
            });
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING,
                    "Не удалось доставить офлайн-платежи для " + player.getName(), ex);
            return null;
        });
    }
}