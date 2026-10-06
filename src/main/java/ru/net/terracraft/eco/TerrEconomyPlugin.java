package ru.net.terracraft.eco;

import net.milkbowl.vault.economy.Economy;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import ru.net.terracraft.eco.command.CommandRegistrar;
import ru.net.terracraft.eco.config.ConfigLoader;
import ru.net.terracraft.eco.config.PluginConfig;
import ru.net.terracraft.eco.db.DatabaseProvider;
import ru.net.terracraft.eco.db.PlayerRepository;
import ru.net.terracraft.eco.economy.EconomyCache;
import ru.net.terracraft.eco.economy.EconomyService;
import ru.net.terracraft.eco.economy.TerrEconomyProvider;
import ru.net.terracraft.eco.lang.LangManager;
import ru.net.terracraft.eco.listener.PlayerConnectionListener;
import ru.net.terracraft.eco.util.NotificationManager;
import ru.net.terracraft.eco.util.UpdateNotifier;

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

    @Override
    public void onEnable() {
        // 1. Конфиг через ConfigLib
        this.configLoader = new ConfigLoader(this);
        this.configLoader.load();
        this.pluginConfig = configLoader.get();

        // 2. Языки
        this.langManager = new LangManager(this, pluginConfig);

        // 3. БД
        this.databaseProvider = new DatabaseProvider(this, pluginConfig);
        if (!databaseProvider.initialize()) {
            getLogger().severe("Не удалось инициализировать БД. Отключаю плагин.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.playerRepository = new PlayerRepository(this, databaseProvider);

        // 4. Кэш, сервис, уведомления
        this.cache = new EconomyCache(pluginConfig);
        this.economyService = new EconomyService(cache, playerRepository, pluginConfig);
        this.notifier = new NotificationManager(pluginConfig, langManager);

        // 5. Vault
        this.economyProvider = new TerrEconomyProvider(this, economyService, pluginConfig);
        getServer().getServicesManager().register(
                Economy.class, economyProvider, this, ServicePriority.Normal);

        // 6. Слушатели
        getServer().getPluginManager().registerEvents(
                new PlayerConnectionListener(cache, playerRepository, pluginConfig, economyService, notifier),
                this);

        // 7. Команды
        new CommandRegistrar(this, economyService, langManager, notifier).register();

        // 8. Автосохранение
        long interval = pluginConfig.economy.autoSaveInterval * 20L;
        getServer().getScheduler().runTaskTimerAsynchronously(this,
                () -> cache.flushAll(playerRepository), interval, interval);

        // 9. bStats
        new Metrics(this, 12345);

        this.updateNotifier = new UpdateNotifier(this, pluginConfig);
        this.updateNotifier.checkAsync();

        getLogger().info("TerrEconomy включён.");
    }

    @Override
    public void onDisable() {
        if (cache != null && playerRepository != null) {
            cache.flushAll(playerRepository);
        }
        if (databaseProvider != null) {
            databaseProvider.close();
        }
        getLogger().info("TerrEconomy выключен.");
    }

    /** Вызывается из /terrEco reload. */
    public void reloadPlugin() {
        configLoader.load();
        this.pluginConfig = configLoader.get();

        langManager.reload();
        this.notifier = new NotificationManager(pluginConfig, langManager);

        getLogger().info("Конфигурация перезагружена.");
    }

    public ConfigLoader getConfigLoader() { return configLoader; }
    public PluginConfig getPluginConfig() { return pluginConfig; }
    public LangManager getLangManager() { return langManager; }
    public EconomyService getEconomyService() { return economyService; }
    public EconomyCache getCache() { return cache; }
    public PlayerRepository getPlayerRepository() { return playerRepository; }
    public NotificationManager getNotifier() { return notifier; }
}