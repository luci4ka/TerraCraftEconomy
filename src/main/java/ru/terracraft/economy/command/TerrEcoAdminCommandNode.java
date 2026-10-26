package ru.terracraft.economy.command;

import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.arguments.Argument;
import dev.jorel.commandapi.arguments.ArgumentSuggestions;
import dev.jorel.commandapi.arguments.DoubleArgument;
import dev.jorel.commandapi.arguments.IntegerArgument;
import dev.jorel.commandapi.arguments.StringArgument;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.terracraft.economy.TerrEconomyPlugin;
import ru.terracraft.economy.economy.EconomyService;
import ru.terracraft.economy.lang.LangKey;
import ru.terracraft.economy.lang.LangManager;
import ru.terracraft.economy.util.PlayerResolver;

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

    /**
     * В CommandAPI 12.x {@code replaceSuggestions} возвращает {@code Argument<String>},
     * а не {@code StringArgument} — поэтому возвращаем {@code Argument<String>}.
     */
    private Argument<String> targetArgument() {
        return new StringArgument("target")
                .replaceSuggestions(ArgumentSuggestions.strings(info ->
                        Bukkit.getOnlinePlayers().stream()
                                .map(Player::getName)
                                .toArray(String[]::new)));
    }

    private CommandAPICommand buildGive() {
        return new CommandAPICommand("give")
                .withPermission(ADMIN_PERMISSION)
                .withArguments(targetArgument())
                .withArguments(new DoubleArgument("amount", 0.0))
                .executes((sender, args) -> {
                    String targetName = (String) args.get("target");
                    OfflinePlayer target = PlayerResolver.resolve(targetName);
                    if (target == null) {
                        send(sender, LangKey.GENERAL_PLAYER_NOT_FOUND, Map.of("target", targetName));
                        return;
                    }
                    BigDecimal value = scale((double) args.get("amount"));
                    BigDecimal newBalance = economyService.deposit(target.getUniqueId(), value);
                    logSelfAction(sender, target, "GIVE", value);
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
                .withArguments(targetArgument())
                .withArguments(new DoubleArgument("amount", 0.0))
                .executes((sender, args) -> {
                    String targetName = (String) args.get("target");
                    OfflinePlayer target = PlayerResolver.resolve(targetName);
                    if (target == null) {
                        send(sender, LangKey.GENERAL_PLAYER_NOT_FOUND, Map.of("target", targetName));
                        return;
                    }
                    BigDecimal value = scale((double) args.get("amount"));
                    BigDecimal newBalance = economyService.withdraw(target.getUniqueId(), value);
                    if (newBalance == null) {
                        BigDecimal current = economyService.getBalance(target.getUniqueId());
                        send(sender, LangKey.ECO_TAKE_INSUFFICIENT,
                                Map.of("balance", current.toPlainString()));
                        return;
                    }
                    logSelfAction(sender, target, "TAKE", value);
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
                .withArguments(targetArgument())
                .withArguments(new DoubleArgument("amount", 0.0))
                .executes((sender, args) -> {
                    String targetName = (String) args.get("target");
                    OfflinePlayer target = PlayerResolver.resolve(targetName);
                    if (target == null) {
                        send(sender, LangKey.GENERAL_PLAYER_NOT_FOUND, Map.of("target", targetName));
                        return;
                    }
                    BigDecimal value = scale((double) args.get("amount"));
                    BigDecimal newBalance = economyService.set(target.getUniqueId(), value);
                    logSelfAction(sender, target, "SET", value);
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
                .withArguments(targetArgument())
                .executes((sender, args) -> {
                    String targetName = (String) args.get("target");
                    OfflinePlayer target = PlayerResolver.resolve(targetName);
                    if (target == null) {
                        send(sender, LangKey.GENERAL_PLAYER_NOT_FOUND, Map.of("target", targetName));
                        return;
                    }
                    var cfg = plugin.getPluginConfig().economy;
                    BigDecimal resetValue = cfg.giveStartingBalance ? cfg.startingBalance : BigDecimal.ZERO;
                    economyService.set(target.getUniqueId(), resetValue);
                    logSelfAction(sender, target, "RESET", resetValue);
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

    private void logSelfAction(CommandSender sender, OfflinePlayer target, String op, BigDecimal amount) {
        if (sender instanceof Player p && p.getUniqueId().equals(target.getUniqueId())) {
            plugin.getLogger().info("Admin " + p.getName() + " performed self-eco "
                    + op + " amount=" + amount.toPlainString());
        }
    }

    private void send(CommandSender sender, LangKey key, Map<String, String> placeholders) {
        if (sender instanceof Player player) {
            sender.sendMessage(lang.msg(player, key, placeholders));
        } else {
            sender.sendMessage(lang.msg(key, placeholders));
        }
    }
}