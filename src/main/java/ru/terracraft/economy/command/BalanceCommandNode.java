package ru.terracraft.economy.command;

import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.arguments.ArgumentSuggestions;
import dev.jorel.commandapi.arguments.StringArgument;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import ru.terracraft.economy.economy.EconomyService;
import ru.terracraft.economy.lang.LangKey;
import ru.terracraft.economy.lang.LangManager;
import ru.terracraft.economy.util.NotificationManager;
import ru.terracraft.economy.util.PlayerResolver;

import java.math.BigDecimal;
import java.util.Map;

public class BalanceCommandNode {

    private static final String PERM_OTHERS = "terracraft.economy.balance.others";

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
                        return;
                    }

                    if (!player.hasPermission(PERM_OTHERS)) {
                        player.sendMessage(lang.msg(player, LangKey.GENERAL_NO_PERMISSION));
                        return;
                    }

                    showOtherBalance(player, targetName);
                })
                .register();
    }

    private void showOtherBalance(Player viewer, String targetName) {
        OfflinePlayer target = PlayerResolver.resolve(targetName);
        if (target == null) {
            viewer.sendMessage(lang.msg(viewer, LangKey.GENERAL_PLAYER_NOT_FOUND,
                    Map.of("target", targetName)));
            return;
        }

        BigDecimal balance = economyService.getBalance(target.getUniqueId());

        if (target.getUniqueId().equals(viewer.getUniqueId())) {
            // /balance <self> → показываем как свой баланс
            notifier.showSelfBalance(viewer, balance);
            return;
        }

        String displayName = target.getName() != null ? target.getName() : targetName;
        notifier.showOtherBalance(viewer, displayName, balance);
    }
}