package ru.terracraft.economy.command;

import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.arguments.ArgumentSuggestions;
import dev.jorel.commandapi.arguments.DoubleArgument;
import dev.jorel.commandapi.arguments.StringArgument;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import ru.terracraft.economy.TerrEconomyPlugin;
import ru.terracraft.economy.config.PluginConfig;
import ru.terracraft.economy.economy.EconomyService;
import ru.terracraft.economy.lang.LangKey;
import ru.terracraft.economy.lang.LangManager;
import ru.terracraft.economy.util.NotificationManager;
import ru.terracraft.economy.util.PendingPayConfirm;
import ru.terracraft.economy.util.PlayerResolver;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

public class PayCommandNode {

    private final TerrEconomyPlugin plugin;
    private final EconomyService economyService;
    private final LangManager lang;
    private final PluginConfig config;
    private final NotificationManager notifier;

    public PayCommandNode(TerrEconomyPlugin plugin, EconomyService economyService, LangManager lang,
                          PluginConfig config, NotificationManager notifier) {
        this.plugin = plugin;
        this.economyService = economyService;
        this.lang = lang;
        this.config = config;
        this.notifier = notifier;
    }

    public void register() {
        new CommandAPICommand("pay")
                .withArguments(new StringArgument("target")
                        .replaceSuggestions(ArgumentSuggestions.strings(info ->
                                Bukkit.getOnlinePlayers().stream()
                                        .map(Player::getName)
                                        .toArray(String[]::new))))
                .withArguments(new DoubleArgument("amount", 0.01))
                .executesPlayer((player, args) -> {
                    String targetName = (String) args.get("target");
                    double rawAmount = (double) args.get("amount");

                    BigDecimal amount = BigDecimal.valueOf(rawAmount)
                            .setScale(config.economy.fractionalDigits, RoundingMode.HALF_UP);

                    if (amount.signum() == 0) {
                        player.sendMessage(lang.msg(player, LangKey.GENERAL_INVALID_AMOUNT));
                        return;
                    }
                    if (amount.signum() < 0) {
                        player.sendMessage(lang.msg(player, LangKey.GENERAL_NEGATIVE_AMOUNT));
                        return;
                    }

                    if (config.pay.confirmThreshold.signum() > 0
                            && amount.compareTo(config.pay.confirmThreshold) >= 0) {
                        PendingPayConfirm previous = plugin.getPendingPayManager()
                                .createAndReturnPrevious(player.getUniqueId(), targetName, amount);

                        if (previous != null && previous.getAmount().compareTo(amount) != 0) {
                            player.sendMessage(lang.msg(player, LangKey.GENERAL_PAYCONFIRM_REPLACED, Map.of(
                                    "old_target", previous.getTargetName(),
                                    "old_amount", previous.getAmount().toPlainString(),
                                    "target", targetName,
                                    "amount", amount.toPlainString(),
                                    "symbol", config.economy.currencySymbol
                            )));
                        } else {
                            player.sendMessage(lang.msg(player, LangKey.GENERAL_PAYCONFIRM, Map.of(
                                    "target", targetName,
                                    "amount", amount.toPlainString(),
                                    "symbol", config.economy.currencySymbol
                            )));
                        }
                        return;
                    }

                    executePayment(player, targetName, amount);
                })
                .register();

        new CommandAPICommand("payconfirm")
                .executesPlayer((player, args) -> {
                    PendingPayConfirm pending = plugin.getPendingPayManager().consume(player.getUniqueId());
                    if (pending == null) {
                        player.sendMessage(lang.msg(player, LangKey.GENERAL_PAYCONFIRM_TIMEOUT));
                        return;
                    }
                    executePayment(player, pending.getTargetName(), pending.getAmount());
                })
                .register();
    }

    private void executePayment(Player sender, String targetName, BigDecimal amount) {
        if (amount.signum() <= 0) {
            sender.sendMessage(lang.msg(sender, amount.signum() == 0
                    ? LangKey.GENERAL_INVALID_AMOUNT
                    : LangKey.GENERAL_NEGATIVE_AMOUNT));
            return;
        }

        OfflinePlayer target = PlayerResolver.resolve(targetName);
        if (target == null) {
            sender.sendMessage(lang.msg(sender, LangKey.GENERAL_PLAYER_NEVER_JOINED,
                    Map.of("target", targetName)));
            return;
        }

        if (target.getUniqueId().equals(sender.getUniqueId())) {
            sender.sendMessage(lang.msg(sender, LangKey.GENERAL_SELF_ACTION));
            return;
        }

        Player onlineTarget = target.isOnline() ? (Player) target : null;

        if (onlineTarget == null && !config.pay.allowOffline) {
            sender.sendMessage(lang.msg(sender, LangKey.GENERAL_PLAYER_NOT_FOUND,
                    Map.of("target", targetName)));
            return;
        }

        BigDecimal senderBalance = economyService.getBalance(sender.getUniqueId());
        if (senderBalance.compareTo(amount) < 0) {
            sender.sendMessage(lang.msg(sender, LangKey.PAY_INSUFFICIENT_FUNDS,
                    Map.of("balance", senderBalance.toPlainString())));
            return;
        }

        BigDecimal tax = calculateTax(amount);
        BigDecimal netAmount = amount.subtract(tax)
                .setScale(config.economy.fractionalDigits, RoundingMode.HALF_UP);

        String targetDisplayName = target.getName() != null ? target.getName() : targetName;

        // Проверка maxBalance получателя (важно и для онлайн, и для офлайн —
        // иначе деньги «сгорят» при deposit/overflow).
        BigDecimal targetBalance = economyService.getBalance(target.getUniqueId());
        BigDecimal maxBalance = config.economy.maxBalance;
        if (targetBalance.add(netAmount).compareTo(maxBalance) > 0) {
            sender.sendMessage(lang.msg(sender, LangKey.PAY_TARGET_MAX_BALANCE, Map.of(
                    "target", targetDisplayName,
                    "max", maxBalance.toPlainString(),
                    "symbol", config.economy.currencySymbol
            )));
            return;
        }

        if (onlineTarget != null) {
            var outcome = economyService.transferWithTaxDetailed(
                    sender.getUniqueId(), onlineTarget.getUniqueId(), amount, tax);

            switch (outcome) {
                case SUCCESS -> {
                    notifier.paymentSent(sender, targetDisplayName, amount, tax, netAmount);
                    notifier.paymentReceived(onlineTarget, sender.getName(), amount, tax, netAmount);
                }
                case INSUFFICIENT -> {
                    BigDecimal current = economyService.getBalance(sender.getUniqueId());
                    sender.sendMessage(lang.msg(sender, LangKey.PAY_INSUFFICIENT_FUNDS,
                            Map.of("balance", current.toPlainString())));
                }
                case TARGET_OVERFLOW -> {
                    BigDecimal currentTarget = economyService.getBalance(onlineTarget.getUniqueId());
                    sender.sendMessage(lang.msg(sender, LangKey.PAY_TARGET_MAX_BALANCE, Map.of(
                            "target", targetDisplayName,
                            "max", currentTarget.toPlainString(),
                            "symbol", config.economy.currencySymbol
                    )));
                }
                case SAME_ACCOUNT -> sender.sendMessage(lang.msg(sender, LangKey.GENERAL_SELF_ACTION));
            }
        } else {
            BigDecimal newSenderBalance = economyService.withdraw(sender.getUniqueId(), amount);
            if (newSenderBalance == null) {
                BigDecimal current = economyService.getBalance(sender.getUniqueId());
                sender.sendMessage(lang.msg(sender, LangKey.PAY_INSUFFICIENT_FUNDS,
                        Map.of("balance", current.toPlainString())));
                return;
            }

            economyService.queueOfflinePayment(
                    target.getUniqueId(), sender.getUniqueId(), netAmount);

            notifier.paymentQueued(sender, targetDisplayName, amount, tax, netAmount);
        }
    }

    private BigDecimal calculateTax(BigDecimal amount) {
        double percent = config.pay.taxPercent;
        if (percent <= 0) return BigDecimal.ZERO;

        BigDecimal percentBd = BigDecimal.valueOf(percent);
        BigDecimal minTaxAmount = config.pay.minTaxAmount;
        BigDecimal maxTaxAmount = config.pay.maxTaxAmount;

        if (minTaxAmount != null && amount.compareTo(minTaxAmount) < 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal tax = amount
                .multiply(percentBd)
                .divide(BigDecimal.valueOf(100), config.economy.fractionalDigits, RoundingMode.HALF_UP);

        if (maxTaxAmount != null && maxTaxAmount.signum() > 0 && tax.compareTo(maxTaxAmount) > 0) {
            tax = maxTaxAmount;
        }
        return tax;
    }
}