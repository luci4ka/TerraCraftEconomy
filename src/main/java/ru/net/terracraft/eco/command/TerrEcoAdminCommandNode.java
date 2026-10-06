package ru.net.terracraft.eco.command;

import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.arguments.*;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.net.terracraft.eco.TerrEconomyPlugin;
import ru.net.terracraft.eco.economy.EconomyService;
import ru.net.terracraft.eco.lang.LangKey;
import ru.net.terracraft.eco.lang.LangManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

public class TerrEcoAdminCommandNode {

    private final TerrEconomyPlugin plugin;
    private final EconomyService economyService;
    private final LangManager lang;

    public TerrEcoAdminCommandNode(TerrEconomyPlugin plugin, EconomyService economyService, LangManager lang) {
        this.plugin = plugin;
        this.economyService = economyService;
        this.lang = lang;
    }

    public void register() {
        new CommandAPICommand("terrEco")
                .withPermission("terracraft.eco.admin")
                .withSubcommand(buildGive())
                .withSubcommand(buildTake())
                .withSubcommand(buildSet())
                .withSubcommand(buildReset())
                .withSubcommand(buildReload())
                .register();
    }

    // ============ /terrEco give <игрок> <сумма> ============

    private CommandAPICommand buildGive() {
        return new CommandAPICommand("give")
                .withArguments(new PlayerProfileArgument("target"))
                .withArguments(new DoubleArgument("amount", 0.0))
                .executes((sender, args) -> {
                    OfflinePlayer target = (OfflinePlayer) args.get("target");
                    double rawAmount = (double) args.get("amount");

                    if (target == null || !economyService.hasAccount(target.getUniqueId())) {
                        send(sender, LangKey.GENERAL_PLAYER_NOT_FOUND,
                                Map.of("target", args.getRaw("target")));
                        return;
                    }

                    BigDecimal value = scale(rawAmount);
                    BigDecimal newBalance = economyService.deposit(target.getUniqueId(), value);

                    Map<String, String> vars = new HashMap<>();
                    vars.put("target", displayName(target));
                    vars.put("amount", value.toPlainString());
                    vars.put("balance", newBalance.toPlainString());
                    send(sender, LangKey.ECO_GIVE, vars);
                });
    }

    // ============ /terrEco take <игрок> <сумма> ============

    private CommandAPICommand buildTake() {
        return new CommandAPICommand("take")
                .withArguments(new PlayerProfileArgument("target"))
                .withArguments(new DoubleArgument("amount", 0.0))
                .executes((sender, args) -> {
                    OfflinePlayer target = (OfflinePlayer) args.get("target");
                    double rawAmount = (double) args.get("amount");

                    if (target == null || !economyService.hasAccount(target.getUniqueId())) {
                        send(sender, LangKey.GENERAL_PLAYER_NOT_FOUND,
                                Map.of("target", args.getRaw("target")));
                        return;
                    }

                    BigDecimal value = scale(rawAmount);
                    BigDecimal newBalance = economyService.withdraw(target.getUniqueId(), value);
                    if (newBalance == null) {
                        BigDecimal current = economyService.getBalance(target.getUniqueId());
                        send(sender, LangKey.ECO_TAKE_INSUFFICIENT,
                                Map.of("balance", current.toPlainString()));
                        return;
                    }

                    Map<String, String> vars = new HashMap<>();
                    vars.put("target", displayName(target));
                    vars.put("amount", value.toPlainString());
                    vars.put("balance", newBalance.toPlainString());
                    send(sender, LangKey.ECO_TAKE, vars);
                });
    }

    // ============ /terrEco set <игрок> <сумма> ============

    private CommandAPICommand buildSet() {
        return new CommandAPICommand("set")
                .withArguments(new PlayerProfileArgument("target"))
                .withArguments(new DoubleArgument("amount", 0.0))
                .executes((sender, args) -> {
                    OfflinePlayer target = (OfflinePlayer) args.get("target");
                    double rawAmount = (double) args.get("amount");

                    if (target == null || !economyService.hasAccount(target.getUniqueId())) {
                        send(sender, LangKey.GENERAL_PLAYER_NOT_FOUND,
                                Map.of("target", args.getRaw("target")));
                        return;
                    }

                    BigDecimal value = scale(rawAmount);
                    BigDecimal newBalance = economyService.set(target.getUniqueId(), value);

                    Map<String, String> vars = new HashMap<>();
                    vars.put("target", displayName(target));
                    vars.put("amount", value.toPlainString());
                    vars.put("balance", newBalance.toPlainString());
                    send(sender, LangKey.ECO_SET, vars);
                });
    }

    // ============ /terrEco reset <игрок> ============

    private CommandAPICommand buildReset() {
        return new CommandAPICommand("reset")
                .withArguments(new PlayerProfileArgument("target"))
                .executes((sender, args) -> {
                    OfflinePlayer target = (OfflinePlayer) args.get("target");

                    if (target == null || !economyService.hasAccount(target.getUniqueId())) {
                        send(sender, LangKey.GENERAL_PLAYER_NOT_FOUND,
                                Map.of("target", args.getRaw("target")));
                        return;
                    }

                    economyService.set(target.getUniqueId(),
                            plugin.getPluginConfig().economy.startingBalance);

                    send(sender, LangKey.ECO_RESET,
                            Map.of("target", displayName(target)));
                });
    }

    // ============ /terrEco reload ============

    private CommandAPICommand buildReload() {
        return new CommandAPICommand("reload")
                .executes((sender, args) -> {
                    plugin.reloadPlugin();
                    send(sender, LangKey.GENERAL_RELOADED, null);
                });
    }

    // ============ Хелперы ============

    private BigDecimal scale(double rawAmount) {
        return BigDecimal.valueOf(rawAmount)
                .setScale(plugin.getPluginConfig().economy.fractionalDigits, RoundingMode.HALF_UP);
    }

    private String displayName(OfflinePlayer target) {
        String name = target.getName();
        return name != null ? name : target.getUniqueId().toString();
    }

    private void send(CommandSender sender, LangKey key, Map<String, String> placeholders) {
        if (sender instanceof Player player) {
            sender.sendMessage(lang.msg(player, key, placeholders));
        } else {
            sender.sendMessage(lang.msg(key, placeholders));
        }
    }
}