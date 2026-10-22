package ru.terracraft.economy.command;

import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.arguments.ArgumentSuggestions;
import dev.jorel.commandapi.arguments.StringArgument;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.terracraft.economy.economy.EconomyService;
import ru.terracraft.economy.lang.LangKey;
import ru.terracraft.economy.lang.LangManager;
import ru.terracraft.economy.util.NotificationManager;

import java.math.BigDecimal;
import java.util.Map;

public class BalanceCommandNode {

    private final EconomyService economyService;
    private final LangManager lang;
    private final NotificationManager notifier;

    public BalanceCommandNode(EconomyService economyService, LangManager lang, NotificationManager notifier) {
        this.economyService = economyService;
        this.lang = lang;
        this.notifier = notifier;
    }

    public void register() {
        new CommandAPICommand("balance")
                .withAliases("bal", "money")
                // Право не указано — команда доступна всем (CommandPermission.NONE)
                .withOptionalArguments(new StringArgument("target")
                        .replaceSuggestions(ArgumentSuggestions.strings(info ->
                                Bukkit.getOnlinePlayers().stream()
                                        .map(Player::getName)
                                        .toArray(String[]::new))))
                .executesPlayer((player, args) -> {
                    String targetName = (String) args.getOptional("target").orElse(null);

                    if (targetName == null) {
                        BigDecimal balance = economyService.getBalance(player.getUniqueId());
                        notifier.showSelfBalance(player, balance);
                    } else {
                        showOtherBalance(player, targetName);
                    }
                })
                .register();
    }

    private void showOtherBalance(CommandSender sender, String targetName) {
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(targetName);
        if (target == null) {
            send(sender, LangKey.GENERAL_PLAYER_NOT_FOUND, Map.of("target", targetName));
            return;
        }

        BigDecimal balance = economyService.getBalance(target.getUniqueId());
        String displayName = target.getName() != null ? target.getName() : targetName;

        if (sender instanceof Player viewer) {
            notifier.showOtherBalance(viewer, displayName, balance);
        } else {
            send(sender, LangKey.BALANCE_OTHER, Map.of(
                    "target", displayName,
                    "balance", balance.toPlainString()
            ));
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