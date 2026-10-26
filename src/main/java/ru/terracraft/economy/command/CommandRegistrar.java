package ru.terracraft.economy.command;

import dev.jorel.commandapi.CommandAPI;
import ru.terracraft.economy.TerrEconomyPlugin;
import ru.terracraft.economy.economy.EconomyService;
import ru.terracraft.economy.lang.LangManager;
import ru.terracraft.economy.util.NotificationManager;

import java.util.List;

public class CommandRegistrar {

    /**
     * Только основные имена (алиасы снимаются вместе с командой).
     * Без force — не снимаем чужие команды с теми же именами.
     */
    private static final List<String> COMMANDS = List.of(
            "balance",
            "pay", "payconfirm",
            "eco"
    );

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
        new PayCommandNode(plugin, economyService, langManager,
                plugin.getPluginConfig(), notifier).register();
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