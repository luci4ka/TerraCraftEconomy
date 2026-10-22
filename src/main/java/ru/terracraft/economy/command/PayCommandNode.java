package ru.terracraft.economy.command;

import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.arguments.ArgumentSuggestions;
import dev.jorel.commandapi.arguments.DoubleArgument;
import dev.jorel.commandapi.arguments.StringArgument;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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

                    // Проверка порога подтверждения
                    if (config.pay.confirmThreshold.signum() > 0
                            && amount.compareTo(config.pay.confirmThreshold) >= 0) {
                        plugin.getPendingPayManager().create(player.getUniqueId(), targetName, amount);
                        player.sendMessage(lang.msg(player, LangKey.GENERAL_PAYCONFIRM, Map.of(
                                "target", targetName,
                                "amount", amount.toPlainString(),
                                "symbol", config.economy.currencySymbol
                        )));
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
        if (sender.getName().equalsIgnoreCase(targetName)) {
            sender.sendMessage(lang.msg(sender, LangKey.GENERAL_SELF_ACTION));
            return;
        }

        Player onlineTarget = Bukkit.getPlayerExact(targetName);
        OfflinePlayer offlineTarget = null;

        if (onlineTarget == null) {
            if (!config.pay.allowOffline) {
                sender.sendMessage(lang.msg(sender, LangKey.GENERAL_PLAYER_NOT_FOUND,
                        Map.of("target", targetName)));
                return;
            }
            offlineTarget = Bukkit.getOfflinePlayerIfCached(targetName);
            if (offlineTarget == null) {
                sender.sendMessage(lang.msg(sender, LangKey.GENERAL_PLAYER_NEVER_JOINED,
                        Map.of("target", targetName)));
                return;
            }
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

        String targetDisplayName = onlineTarget != null
                ? onlineTarget.getName()
                : offlineTarget.getName();

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