package ru.terracraft.economy.command;

import dev.jorel.commandapi.CommandAPI;
import ru.terracraft.economy.TerrEconomyPlugin;
import ru.terracraft.economy.economy.EconomyService;
import ru.terracraft.economy.lang.LangManager;
import ru.terracraft.economy.util.NotificationManager;

import java.util.List;

public class CommandRegistrar {

    /** Имена всех команд, которые регистрирует этот класс (включая подкоманды /eco). */
    private static final List<String> COMMANDS = List.of(
            "balance", "bal", "money",
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

    /**
     * Снимает все команды, зарегистрированные этим классом.
     * CommandAPI.unregister() бросает исключение, если команда не найдена,
     * поэтому оборачиваем в try/catch и логируем на debug-уровне.
     */
    public void unregister() {
        for (String name : COMMANDS) {
            try {
                CommandAPI.unregister(name, true);
            } catch (Exception e) {
                // Команда могла быть не зарегистрирована — это не ошибка.
                plugin.getLogger().fine("unregister(" + name + "): " + e.getMessage());
            }
        }
    }
}