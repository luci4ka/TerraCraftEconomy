package ru.terracraft.economy.command;

import ru.terracraft.economy.TerrEconomyPlugin;
import ru.terracraft.economy.economy.EconomyService;
import ru.terracraft.economy.lang.LangManager;
import ru.terracraft.economy.util.NotificationManager;

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
        new PayCommandNode(plugin, economyService, langManager, plugin.getPluginConfig(), notifier).register();
        new TerrEcoAdminCommandNode(plugin, economyService, langManager).register();
    }
}