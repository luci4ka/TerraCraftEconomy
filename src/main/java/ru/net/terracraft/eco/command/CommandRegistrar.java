package ru.net.terracraft.eco.command;

import ru.net.terracraft.eco.TerrEconomyPlugin;
import ru.net.terracraft.eco.economy.EconomyService;
import ru.net.terracraft.eco.lang.LangManager;
import ru.net.terracraft.eco.util.NotificationManager;

public class CommandRegistrar {

    private final TerrEconomyPlugin plugin;
    private final EconomyService economyService;
    private final LangManager langManager;
    private final NotificationManager notifier;

    public CommandRegistrar(TerrEconomyPlugin plugin, EconomyService economyService,
                            LangManager langManager, NotificationManager notifier) {
        this.plugin = plugin;
        this.economyService = economyService;
        this.langManager = langManager;
        this.notifier = notifier;
    }

    public void register() {
        new BalanceCommandNode(economyService, langManager, notifier).register();
        new PayCommandNode(economyService, langManager, plugin.getPluginConfig(), notifier).register();
        new TerrEcoAdminCommandNode(plugin, economyService, langManager).register();
    }
}