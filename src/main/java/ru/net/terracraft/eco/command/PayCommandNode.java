package ru.net.terracraft.eco.command;

import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.arguments.ArgumentSuggestions;
import dev.jorel.commandapi.arguments.DoubleArgument;
import dev.jorel.commandapi.arguments.StringArgument;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.net.terracraft.eco.config.PluginConfig;
import ru.net.terracraft.eco.economy.EconomyService;
import ru.net.terracraft.eco.lang.LangKey;
import ru.net.terracraft.eco.lang.LangManager;
import ru.net.terracraft.eco.util.NotificationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

public class PayCommandNode {

    private final EconomyService economyService;
    private final LangManager lang;
    private final PluginConfig config;
    private final NotificationManager notifier;

    public PayCommandNode(EconomyService economyService, LangManager lang,
                          PluginConfig config, NotificationManager notifier) {
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

                    executePayment(player, targetName, amount);
                })
                .register();
    }

    private void executePayment(Player sender, String targetName, BigDecimal amount) {
        // 1. Нельзя переводить себе
        if (sender.getName().equalsIgnoreCase(targetName)) {
            sender.sendMessage(lang.msg(sender, LangKey.GENERAL_SELF_ACTION));
            return;
        }

        // 2. Резолвим получателя
        Player onlineTarget = Bukkit.getPlayerExact(targetName);
        OfflinePlayer offlineTarget = null;

        if (onlineTarget == null) {
            if (!config.pay.allowOffline) {
                sender.sendMessage(lang.msg(sender, LangKey.GENERAL_PLAYER_NOT_FOUND,
                        Map.of("target", targetName)));
                return;
            }
            offlineTarget = Bukkit.getOfflinePlayerIfCached(targetName);
            if (offlineTarget == null || !economyService.hasAccount(offlineTarget.getUniqueId())) {
                sender.sendMessage(lang.msg(sender, LangKey.GENERAL_PLAYER_NEVER_JOINED,
                        Map.of("target", targetName)));
                return;
            }
        }

        // 3. Проверка баланса
        BigDecimal senderBalance = economyService.getBalance(sender.getUniqueId());
        if (senderBalance.compareTo(amount) < 0) {
            sender.sendMessage(lang.msg(sender, LangKey.PAY_INSUFFICIENT_FUNDS,
                    Map.of("balance", senderBalance.toPlainString())));
            return;
        }

        // 4. Налог
        BigDecimal tax = calculateTax(amount);
        BigDecimal netAmount = amount.subtract(tax)
                .setScale(config.economy.fractionalDigits, RoundingMode.HALF_UP);

        String targetDisplayName = onlineTarget != null
                ? onlineTarget.getName()
                : offlineTarget.getName();

        // 5. Онлайн-перевод
        if (onlineTarget != null) {
            boolean success = economyService.transferWithTax(
                    sender.getUniqueId(), onlineTarget.getUniqueId(), amount, tax);

            if (!success) {
                sender.sendMessage(lang.msg(sender, LangKey.PAY_INSUFFICIENT_FUNDS,
                        Map.of("balance", senderBalance.toPlainString())));
                return;
            }

            notifier.paymentSent(sender, targetDisplayName, amount, tax, netAmount);
            notifier.paymentReceived(onlineTarget, sender.getName(), amount, tax, netAmount);

            // 6. Офлайн-перевод
        } else {
            BigDecimal newSenderBalance = economyService.withdraw(sender.getUniqueId(), amount);
            if (newSenderBalance == null) {
                sender.sendMessage(lang.msg(sender, LangKey.PAY_INSUFFICIENT_FUNDS,
                        Map.of("balance", senderBalance.toPlainString())));
                return;
            }

            economyService.queueOfflinePayment(
                    offlineTarget.getUniqueId(), sender.getUniqueId(), netAmount);

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