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
import ru.terracraft.economy.util.NotificationManager;
import ru.terracraft.economy.util.PendingPayManager;
import ru.terracraft.economy.util.UpdateNotifier;

public final class TerrEconomyPlugin extends JavaPlugin {

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
    private CommandRegistrar commandRegistrar;

    private int autoSaveTaskId = -1;
    private int pendingCleanupTaskId = -1;

    @Override
    public void onEnable() {
        this.configLoader = new ConfigLoader(this);
        this.configLoader.load();
        this.pluginConfig = configLoader.get();

        this.langManager = new LangManager(this, pluginConfig);

        getServer().getScheduler().runTaskLater(this, this::initializeEconomy, 1L);
    }

    private void initializeEconomy() {
        this.databaseProvider = new DatabaseProvider(this, pluginConfig);
        if (!databaseProvider.initialize()) {
            getLogger().severe("Не удалось инициализировать БД. Отключаю плагин.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.playerRepository = new PlayerRepository(this, databaseProvider);

        this.cache = new EconomyCache(pluginConfig);
        this.economyService = new EconomyService(cache, playerRepository, pluginConfig);

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new TerrEconomyExpansion(this, economyService, pluginConfig, langManager).register();
        }

        this.notifier = new NotificationManager(pluginConfig, langManager);
        this.pendingPayManager = new PendingPayManager(pluginConfig);

        this.economyProvider = new TerrEconomyProvider(this, economyService, pluginConfig);
        getServer().getServicesManager().register(
                Economy.class, economyProvider, this, ServicePriority.Normal);

        getServer().getPluginManager().registerEvents(
                new PlayerConnectionListener(this, cache, playerRepository, pluginConfig, economyService, notifier),
                this);

        this.commandRegistrar = new CommandRegistrar(this, economyService, langManager, notifier);
        this.commandRegistrar.register();

        scheduleAutoSave();
        schedulePendingCleanup();

        new Metrics(this, 12345);

        this.updateNotifier = new UpdateNotifier(this, pluginConfig);
        this.updateNotifier.checkAsync();

        getLogger().info("TerrEconomy включён.");
    }

    private void scheduleAutoSave() {
        long interval = Math.max(1L, pluginConfig.economy.autoSaveInterval) * 20L;
        autoSaveTaskId = getServer().getScheduler()
                .runTaskTimerAsynchronously(this, () -> {
                    if (playerRepository == null || cache == null) return;
                    playerRepository.flushAll(cache.snapshot());
                }, interval, interval)
                .getTaskId();
    }

    private void schedulePendingCleanup() {
        pendingCleanupTaskId = getServer().getScheduler()
                .runTaskTimerAsynchronously(this, pendingPayManager::cleanup, 1200L, 1200L)
                .getTaskId();
    }

    @Override
    public void onDisable() {
        if (autoSaveTaskId != -1) {
            getServer().getScheduler().cancelTask(autoSaveTaskId);
        }
        if (pendingCleanupTaskId != -1) {
            getServer().getScheduler().cancelTask(pendingCleanupTaskId);
        }

        if (commandRegistrar != null) {
            commandRegistrar.unregister();
        }

        // 1) Сброс балансов до остановки executor'а.
        if (cache != null && playerRepository != null) {
            playerRepository.flushAll(cache.snapshot());
        }

        // 2) Останов executor'а (дожидается очереди).
        if (playerRepository != null) {
            playerRepository.shutdown();
        }

        if (databaseProvider != null) {
            databaseProvider.close();
        }
        getLogger().info("TerrEconomy выключен.");
    }

    /**
     * Перезагрузка конфигурации. Важно: не пересоздаём компоненты, держащие
     * ссылку на {@link PluginConfig}, — вместо этого обновляем поля в том же
     * инстансе через {@link ConfigLoader#load()} (он мутирует {@code get()}).
     */
    public void reloadPlugin() {
        configLoader.load();
        langManager.reload();

        // Пересоздаём только те компоненты, которые читают конфиг при вызове
        // (notifier держит ссылку на config — пересоздаём, чтобы обновить).
        this.notifier = new NotificationManager(pluginConfig, langManager);

        if (commandRegistrar != null) {
            commandRegistrar.unregister();
        }
        this.commandRegistrar = new CommandRegistrar(this, economyService, langManager, notifier);
        this.commandRegistrar.register();

        // Перезапуск автосейва с новым интервалом.
        if (autoSaveTaskId != -1) {
            getServer().getScheduler().cancelTask(autoSaveTaskId);
            scheduleAutoSave();
        }

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
}