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

        // Всё, что касается БД, — в фоновом потоке
        repo.existsAsync(uuid).thenCompose(exists -> {
            if (!exists) {
                // Новый игрок: создаём счёт в кэше (главный поток) и в БД (async)
                return CompletableFuture.completedFuture((BigDecimal) null);
            }
            return repo.getBalanceAsync(uuid).thenApply(balance -> balance);
        }).thenAccept(balance -> {
            // Возвращаемся в главный поток для работы с кэшем и игроком
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;

                if (balance == null) {
                    // Новый игрок
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
        repo.updateBalanceAsync(uuid, cache.getBalance(uuid));
    }

    private void deliverPendingPayments(Player player) {
        UUID uuid = player.getUniqueId();

        repo.getPendingPaymentsAsync(uuid).thenCompose(pending -> {
            if (pending.isEmpty()) {
                return CompletableFuture.completedFuture((Void) null);
            }

            // Зачисляем платежи в кэш — это должно быть в главном потоке
            BigDecimal total = BigDecimal.ZERO;
            for (PlayerRepository.PendingPayment p : pending) {
                total = total.add(p.amount());
            }
            final BigDecimal finalTotal = total;

            // Удаляем записи из БД асинхронно
            for (PlayerRepository.PendingPayment p : pending) {
                repo.deletePendingPaymentAsync(p.id());
            }

            // Последний отправитель для отображения
            UUID lastSenderUuid = pending.get(pending.size() - 1).senderUuid();
            return repo.getNameByUuidAsync(lastSenderUuid).thenAccept(lastSender -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;

                    // Зачисляем в кэш и показываем уведомление
                    for (PlayerRepository.PendingPayment p : pending) {
                        economyService.deposit(uuid, p.amount());
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