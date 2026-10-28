package ru.terracraft.economy;

import net.milkbowl.vault.economy.Economy;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import ru.terracraft.economy.command.CommandRegistrar;
import ru.terracraft.economy.config.ConfigLoader;
import ru.terracraft.economy.config.PluginConfig;
import ru.terracraft.economy.db.DatabaseProvider;
import ru.terracraft.economy.db.PlayerRepository;
import ru.terracraft.economy.economy.EconomyCache;
import ru.terracraft.economy.economy.EconomyService;
import ru.terracraft.economy.economy.TerrEconomyProvider;
import ru.terracraft.economy.hook.TerrEconomyExpansion;
import ru.terracraft.economy.lang.LangManager;
import ru.terracraft.economy.listener.PlayerConnectionListener;
import ru.terracraft.economy.util.CooldownManager;
import ru.terracraft.economy.util.NotificationManager;
import ru.terracraft.economy.util.PendingPayManager;
import ru.terracraft.economy.util.PlayerResolver;
import ru.terracraft.economy.util.UpdateNotifier;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public final class TerrEconomyPlugin extends JavaPlugin {

    private static final int BSTATS_ID = 12345;
    private static final long ORPHANED_CLEANUP_PERIOD_TICKS = 72_000L;

    private ConfigLoader configLoader;
    private PluginConfig pluginConfig;

    private DatabaseProvider databaseProvider;
    private PlayerRepository playerRepository;
    private EconomyCache cache;
    private UpdateNotifier updateNotifier;
    private EconomyService economyService;
    private TerrEconomyProvider economyProvider;
    private LangManager langManager;
    private NotificationManager notifier;
    private PendingPayManager pendingPayManager;
    private CooldownManager cooldownManager;
    private CommandRegistrar commandRegistrar;
    private TerrEconomyExpansion papiExpansion;

    private int autoSaveTaskId = -1;
    private int pendingCleanupTaskId = -1;
    private int orphanedCleanupTaskId = -1;
    private int evictTaskId = -1;
    private boolean economyRegistered = false;

    @Override
    public void onLoad() {
        this.configLoader = new ConfigLoader(this);
        this.configLoader.load();
        this.pluginConfig = configLoader.get();

        this.langManager = new LangManager(this, pluginConfig);

        this.databaseProvider = new DatabaseProvider(this, pluginConfig);
        if (!databaseProvider.initialize()) {
            getLogger().severe("Не удалось инициализировать БД. Отключаю плагин.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.playerRepository = new PlayerRepository(this, databaseProvider, pluginConfig);

        this.cache = new EconomyCache(pluginConfig);
        this.economyService = new EconomyService(cache, playerRepository, pluginConfig);
        this.economyProvider = new TerrEconomyProvider(this, economyService, pluginConfig);

        getServer().getServicesManager().register(
                Economy.class,
                economyProvider,
                this,
                ServicePriority.High);
        this.economyRegistered = true;

        getLogger().info("Economy-сервис зарегистрирован (onLoad).");
    }

    @Override
    public void onEnable() {
        if (!economyRegistered) {
            getLogger().severe("Economy-сервис не зарегистрирован — выключаю плагин.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        registerPapi();

        this.notifier = new NotificationManager(pluginConfig, langManager);
        this.pendingPayManager = new PendingPayManager(pluginConfig);
        this.cooldownManager = new CooldownManager(pluginConfig);

        getServer().getPluginManager().registerEvents(
                new PlayerConnectionListener(this, cache, playerRepository,
                        pluginConfig, economyService, notifier),
                this);

        this.commandRegistrar = new CommandRegistrar(
                this, economyService, langManager, notifier, cooldownManager);
        this.commandRegistrar.register();

        scheduleAutoSave();
        schedulePendingCleanup();
        scheduleOrphanedCleanup();
        scheduleEviction();

        new Metrics(this, BSTATS_ID);

        this.updateNotifier = new UpdateNotifier(this, pluginConfig);
        this.updateNotifier.checkAsync();

        getLogger().info("TerrEconomy включён.");
    }

    private void registerPapi() {
        if (!getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) return;
        this.papiExpansion = new TerrEconomyExpansion(this, economyService, pluginConfig, langManager);
        this.papiExpansion.register();
    }

    private void unregisterPapi() {
        if (this.papiExpansion == null) return;
        try {
            this.papiExpansion.unregister();
        } catch (RuntimeException e) {
            getLogger().fine("unregister PAPI: " + e.getMessage());
        }
        this.papiExpansion = null;
    }

    /**
     * Автосейв: инкрементальный — только изменённые с прошлого вызова.
     * <p>При сбое БД ({@code flushAll == false}) dirty-set <b>не очищается</b>
     * — данные повторятся на следующем тике. Полный {@link EconomyCache#snapshot()}
     * используется только в {@code onDisable}/{@code reloadPlugin}.
     */
    private void scheduleAutoSave() {
        long interval = Math.max(1L, pluginConfig.economy.autoSaveInterval) * 20L;
        autoSaveTaskId = getServer().getScheduler()
                .runTaskTimerAsynchronously(this, () -> {
                    if (playerRepository == null || economyService == null) return;
                    Map<UUID, BigDecimal> dirty = economyService.snapshotDirty();
                    if (dirty.isEmpty()) return;
                    boolean ok = playerRepository.flushAll(dirty);
                    if (ok) {
                        economyService.clearDirty(dirty.keySet());
                    } else {
                        getLogger().warning(
                                "Автосейв не удался — " + dirty.size()
                                        + " записей останутся в dirty-set до следующего раза.");
                    }
                }, interval, interval)
                .getTaskId();
    }

    private void schedulePendingCleanup() {
        pendingCleanupTaskId = getServer().getScheduler()
                .runTaskTimerAsynchronously(this, pendingPayManager::cleanup, 1200L, 1200L)
                .getTaskId();
    }

    private void scheduleOrphanedCleanup() {
        orphanedCleanupTaskId = getServer().getScheduler()
                .runTaskTimerAsynchronously(this, () -> {
                    if (playerRepository == null) return;
                    int maxAgeDays = Math.max(1, pluginConfig.pay.orphanedPendingMaxAgeDays);
                    playerRepository.deleteOrphanedPendingPaymentsAsync(maxAgeDays)
                            .thenAccept(removed -> {
                                if (removed > 0) {
                                    getLogger().info("Удалено «мёртвых» pending-платежей: " + removed);
                                }
                            });
                }, ORPHANED_CLEANUP_PERIOD_TICKS, ORPHANED_CLEANUP_PERIOD_TICKS)
                .getTaskId();
    }

    private void scheduleEviction() {
        long interval = Math.max(1L, pluginConfig.economy.autoSaveInterval) * 20L * 4;
        evictTaskId = getServer().getScheduler()
                .runTaskTimerAsynchronously(this, () -> {
                    if (cache == null || playerRepository == null) return;
                    long ttl = Math.max(1L, pluginConfig.economy.autoSaveInterval) * 1000L * 4;
                    int removed = cache.evictIdle(ttl, uuid -> getServer().getPlayer(uuid) != null);
                    if (removed > 0) {
                        getLogger().fine("EconomyCache evicted " + removed + " idle entries.");
                    }
                }, interval, interval)
                .getTaskId();
    }

    @Override
    public void onDisable() {
        cancelTask(autoSaveTaskId);
        autoSaveTaskId = -1;
        cancelTask(pendingCleanupTaskId);
        pendingCleanupTaskId = -1;
        cancelTask(orphanedCleanupTaskId);
        orphanedCleanupTaskId = -1;
        cancelTask(evictTaskId);
        evictTaskId = -1;

        if (commandRegistrar != null) {
            commandRegistrar.unregister();
        }
        unregisterPapi();
        PlayerResolver.clearCache();
        if (cooldownManager != null) {
            cooldownManager.clearAll();
        }

        // Полный flush — гарантия, что все балансы уйдут в БД.
        if (cache != null && playerRepository != null) {
            try {
                playerRepository.flushAll(cache.snapshot());
            } catch (Throwable t) {
                getLogger().warning("Не удалось сохранить балансы при выключении: " + t.getMessage());
            }
        }

        if (playerRepository != null) {
            playerRepository.shutdown();
        }

        if (databaseProvider != null) {
            databaseProvider.close();
        }

        if (economyRegistered) {
            getServer().getServicesManager().unregister(Economy.class, economyProvider);
            economyRegistered = false;
        }

        getLogger().info("TerrEconomy выключен.");
    }

    private void cancelTask(int id) {
        if (id != -1) {
            getServer().getScheduler().cancelTask(id);
        }
    }

    public void reloadPlugin() {
        if (!economyRegistered) {
            getLogger().warning("Перезагрузка невозможна: плагин ещё не инициализирован.");
            return;
        }

        configLoader.load();
        langManager.reload();
        PlayerResolver.clearCache();

        this.notifier = new NotificationManager(pluginConfig, langManager);
        this.pendingPayManager = new PendingPayManager(pluginConfig);
        this.cooldownManager.clearAll();
        this.cooldownManager = new CooldownManager(pluginConfig);

        if (commandRegistrar != null) {
            commandRegistrar.unregister();
        }
        this.commandRegistrar = new CommandRegistrar(
                this, economyService, langManager, notifier, cooldownManager);
        this.commandRegistrar.register();

        unregisterPapi();
        registerPapi();

        // Перед перезапуском автосейва с новым интервалом — полный flush.
        if (cache != null && playerRepository != null) {
            try {
                playerRepository.flushAll(cache.snapshot());
            } catch (Throwable t) {
                getLogger().warning("Не удалось сохранить балансы при reload: " + t.getMessage());
            }
        }

        if (autoSaveTaskId != -1) {
            getServer().getScheduler().cancelTask(autoSaveTaskId);
        }
        scheduleAutoSave();

        if (pendingCleanupTaskId != -1) {
            getServer().getScheduler().cancelTask(pendingCleanupTaskId);
        }
        schedulePendingCleanup();

        if (orphanedCleanupTaskId != -1) {
            getServer().getScheduler().cancelTask(orphanedCleanupTaskId);
        }
        scheduleOrphanedCleanup();

        if (evictTaskId != -1) {
            getServer().getScheduler().cancelTask(evictTaskId);
        }
        scheduleEviction();

        getLogger().info("Конфигурация перезагружена.");
    }

    public ConfigLoader getConfigLoader() { return configLoader; }
    public PluginConfig getPluginConfig() { return pluginConfig; }
    public LangManager getLangManager() { return langManager; }
    public EconomyService getEconomyService() { return economyService; }
    public EconomyCache getCache() { return cache; }
    public PlayerRepository getPlayerRepository() { return playerRepository; }
    public NotificationManager getNotifier() { return notifier; }
    public PendingPayManager getPendingPayManager() { return pendingPayManager; }
    public CooldownManager getCooldownManager() { return cooldownManager; }
}