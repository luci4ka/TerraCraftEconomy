package ru.terracraft.economy.command;

import dev.jorel.commandapi.CommandAPI;
import ru.terracraft.economy.TerrEconomyPlugin;
import ru.terracraft.economy.economy.EconomyService;
import ru.terracraft.economy.lang.LangManager;
import ru.terracraft.economy.util.CooldownManager;
import ru.terracraft.economy.util.NotificationManager;

import java.util.List;

public class CommandRegistrar {

    private static final List<String> COMMANDS = List.of(
            "balance",
            "pay", "payconfirm",
            "eco"
    );

    private final TerrEconomyPlugin plugin;
    private final EconomyService economyService;
    private final LangManager langManager;
    private final NotificationManager notifier;
    private final CooldownManager cooldownManager;

    public CommandRegistrar(TerrEconomyPlugin plugin, EconomyService economyService,
                            LangManager langManager, NotificationManager notifier,
                            CooldownManager cooldownManager) {
        this.plugin = plugin;
        this.economyService = economyService;
        this.langManager = langManager;
        this.notifier = notifier;
        this.cooldownManager = cooldownManager;
    }

    public void register() {
        new BalanceCommandNode(economyService, langManager, notifier, cooldownManager,
                plugin.getPluginConfig()).register();
        new PayCommandNode(plugin, economyService, langManager,
                plugin.getPluginConfig(), notifier, cooldownManager).register();
        new TerrEcoAdminCommandNode(plugin, economyService, langManager).register();
    }

    public void unregister() {
        for (String name : COMMANDS) {
            try {
                CommandAPI.unregister(name);
            } catch (Exception e) {
                plugin.getLogger().fine("unregister(" + name + "): " + e.getMessage());
            }
        }
    }
}