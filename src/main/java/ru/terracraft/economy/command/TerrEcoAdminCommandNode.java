package ru.terracraft.economy.command;

import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.arguments.*;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.terracraft.economy.TerrEconomyPlugin;
import ru.terracraft.economy.economy.EconomyService;
import ru.terracraft.economy.lang.LangKey;
import ru.terracraft.economy.lang.LangManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

public class TerrEcoAdminCommandNode {

    private static final String ADMIN_PERMISSION = "terracraft.economy.admin";

    private final TerrEconomyPlugin plugin;
    private final EconomyService economyService;
    private final LangManager lang;

    public TerrEcoAdminCommandNode(TerrEconomyPlugin plugin, EconomyService economyService, LangManager lang) {
        this.plugin = plugin;
        this.economyService = economyService;
        this.lang = lang;
    }

    public void register() {
        new CommandAPICommand("eco")
                .withPermission(ADMIN_PERMISSION)
                .withSubcommand(buildGive())
                .withSubcommand(buildTake())
                .withSubcommand(buildSet())
                .withSubcommand(buildReset())
                .withSubcommand(buildCleanup())
                .withSubcommand(buildReload())
                .register();
    }

    private CommandAPICommand buildGive() {
        return new CommandAPICommand("give")
                .withPermission(ADMIN_PERMISSION)
                .withArguments(new PlayerProfileArgument("target"))
                .withArguments(new DoubleArgument("amount", 0.0))
                .executes((sender, args) -> {
                    OfflinePlayer target = (OfflinePlayer) args.get("target");
                    double rawAmount = (double) args.get("amount");
                    if (target == null) {
                        send(sender, LangKey.GENERAL_PLAYER_NOT_FOUND, Map.of("target", args.getRaw("target")));
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

    private CommandAPICommand buildTake() {
        return new CommandAPICommand("take")
                .withPermission(ADMIN_PERMISSION)
                .withArguments(new PlayerProfileArgument("target"))
                .withArguments(new DoubleArgument("amount", 0.0))
                .executes((sender, args) -> {
                    OfflinePlayer target = (OfflinePlayer) args.get("target");
                    double rawAmount = (double) args.get("amount");
                    if (target == null) {
                        send(sender, LangKey.GENERAL_PLAYER_NOT_FOUND, Map.of("target", args.getRaw("target")));
                        return;
                    }
                    BigDecimal value = scale(rawAmount);
                    BigDecimal newBalance = economyService.withdraw(target.getUniqueId(), value);
                    if (newBalance == null) {
                        BigDecimal current = economyService.getBalance(target.getUniqueId());
                        send(sender, LangKey.ECO_TAKE_INSUFFICIENT, Map.of("balance", current.toPlainString()));
                        return;
                    }
                    Map<String, String> vars = new HashMap<>();
                    vars.put("target", displayName(target));
                    vars.put("amount", value.toPlainString());
                    vars.put("balance", newBalance.toPlainString());
                    send(sender, LangKey.ECO_TAKE, vars);
                });
    }

    private CommandAPICommand buildSet() {
        return new CommandAPICommand("set")
                .withPermission(ADMIN_PERMISSION)
                .withArguments(new PlayerProfileArgument("target"))
                .withArguments(new DoubleArgument("amount", 0.0))
                .executes((sender, args) -> {
                    OfflinePlayer target = (OfflinePlayer) args.get("target");
                    double rawAmount = (double) args.get("amount");
                    if (target == null) {
                        send(sender, LangKey.GENERAL_PLAYER_NOT_FOUND, Map.of("target", args.getRaw("target")));
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

    private CommandAPICommand buildReset() {
        return new CommandAPICommand("reset")
                .withPermission(ADMIN_PERMISSION)
                .withArguments(new PlayerProfileArgument("target"))
                .executes((sender, args) -> {
                    OfflinePlayer target = (OfflinePlayer) args.get("target");
                    if (target == null) {
                        send(sender, LangKey.GENERAL_PLAYER_NOT_FOUND, Map.of("target", args.getRaw("target")));
                        return;
                    }
                    economyService.set(target.getUniqueId(), plugin.getPluginConfig().economy.startingBalance);
                    send(sender, LangKey.ECO_RESET, Map.of("target", displayName(target)));
                });
    }

    private CommandAPICommand buildCleanup() {
        return new CommandAPICommand("cleanup")
                .withPermission(ADMIN_PERMISSION)
                .withArguments(new IntegerArgument("days", 1))
                .executes((sender, args) -> {
                    int days = (int) args.get("days");
                    int removed = plugin.getPlayerRepository().deleteInactiveAccounts(days);
                    send(sender, LangKey.ECO_CLEANUP, Map.of("count", String.valueOf(removed)));
                });
    }

    private CommandAPICommand buildReload() {
        return new CommandAPICommand("reload")
                .withPermission(ADMIN_PERMISSION)
                .executes((sender, args) -> {
                    plugin.reloadPlugin();
                    send(sender, LangKey.GENERAL_RELOADED, null);
                });
    }

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