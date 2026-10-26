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

    @Override
    public void onEnable() {
        // 1. Конфиг и локализация — синхронно, они не зависят от БД.
        this.configLoader = new ConfigLoader(this);
        this.configLoader.load();
        this.pluginConfig = configLoader.get();

        this.langManager = new LangManager(this, pluginConfig);

        // 2. Инициализация БД откладывается на 1 тик, чтобы MineSQL успел
        //    завершить onEnable и зарегистрировать источники данных.
        getServer().getScheduler().runTaskLater(this, this::initializeEconomy, 1L);
    }

    /**
     * Вторая фаза инициализации: БД, кэш, сервис, Vault, команды, задачи.
     * Вызывается через 1 тик после onEnable.
     */
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

        // Автосохранение
        long interval = pluginConfig.economy.autoSaveInterval * 20L;
        getServer().getScheduler().runTaskTimerAsynchronously(this,
                () -> cache.flushAll(playerRepository), interval, interval);

        // Очистка просроченных подтверждений (раз в минуту)
        getServer().getScheduler().runTaskTimerAsynchronously(this,
                pendingPayManager::cleanup, 1200L, 1200L);

        new Metrics(this, 12345);

        this.updateNotifier = new UpdateNotifier(this, pluginConfig);
        this.updateNotifier.checkAsync();

        getLogger().info("TerrEconomy включён.");
    }

    @Override
    public void onDisable() {
        if (commandRegistrar != null) {
            commandRegistrar.unregister();
        }

        if (playerRepository != null) {
            playerRepository.shutdown();
        }

        if (cache != null && playerRepository != null) {
            cache.flushAll(playerRepository);
        }

        if (databaseProvider != null) {
            databaseProvider.close();
        }
        getLogger().info("TerrEconomy выключен.");
    }

    public void reloadPlugin() {
        configLoader.load();
        this.pluginConfig = configLoader.get();
        langManager.reload();
        this.notifier = new NotificationManager(pluginConfig, langManager);

        if (commandRegistrar != null) {
            commandRegistrar.unregister();
        }
        this.commandRegistrar = new CommandRegistrar(this, economyService, langManager, notifier);
        this.commandRegistrar.register();

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