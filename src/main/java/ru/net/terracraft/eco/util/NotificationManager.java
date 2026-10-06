package ru.net.terracraft.eco.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import ru.net.terracraft.eco.config.PluginConfig;
import ru.net.terracraft.eco.lang.LangKey;
import ru.net.terracraft.eco.lang.LangManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

public class NotificationManager {

    private final PluginConfig config;
    private final LangManager lang;

    public NotificationManager(PluginConfig config, LangManager lang) {
        this.config = config;
        this.lang = lang;
    }

    // ============ Перевод выполнен (отправитель) ============

    public void paymentSent(Player sender, String targetName,
                            BigDecimal amount, BigDecimal tax, BigDecimal netAmount) {
        sender.sendMessage(buildReceipt(
                sender,
                lang.msgNoPrefix(sender, LangKey.NOTIFY_PAY_SENT_TITLE),
                lang.msgNoPrefix(sender, LangKey.NOTIFY_PAY_LABEL_TARGET,
                        Map.of("target", targetName)),
                targetName,
                amount, tax, netAmount,
                NamedTextColor.GOLD
        ));

        sender.sendActionBar(lang.msgNoPrefix(sender, LangKey.NOTIFY_PAY_ACTION_SENT, Map.of(
                "amount", amount.toPlainString(),
                "symbol", config.economy.currencySymbol
        )).color(NamedTextColor.RED));
    }

    // ============ Перевод получен (получатель онлайн) ============

    public void paymentReceived(Player target, String senderName,
                                BigDecimal amount, BigDecimal tax, BigDecimal netAmount) {
        target.showTitle(Title.title(
                lang.msgNoPrefix(target, LangKey.NOTIFY_PAY_RECEIVED_TITLE)
                        .color(NamedTextColor.GREEN)
                        .decoration(TextDecoration.BOLD, true),
                lang.msgNoPrefix(target, LangKey.NOTIFY_PAY_RECEIVED_SUBTITLE, Map.of(
                                "amount", netAmount.toPlainString(),
                                "sender", senderName,
                                "symbol", config.economy.currencySymbol))
                        .color(NamedTextColor.GRAY),
                Title.Times.times(
                        Duration.ofMillis(200),
                        Duration.ofSeconds(2),
                        Duration.ofMillis(500))
        ));

        target.sendActionBar(lang.msgNoPrefix(target, LangKey.NOTIFY_PAY_ACTION_RECEIVED, Map.of(
                "amount", netAmount.toPlainString(),
                "symbol", config.economy.currencySymbol
        )).color(NamedTextColor.GREEN));

        target.sendMessage(buildReceipt(
                target,
                lang.msgNoPrefix(target, LangKey.NOTIFY_PAY_RECEIVED_TITLE),
                lang.msgNoPrefix(target, LangKey.NOTIFY_PAY_LABEL_SENDER,
                        Map.of("sender", senderName)),
                senderName,
                amount, tax, netAmount,
                NamedTextColor.GREEN
        ));
    }

    // ============ Офлайн-перевод (отправитель) ============

    public void paymentQueued(Player sender, String targetName,
                              BigDecimal amount, BigDecimal tax, BigDecimal netAmount) {
        String line = "━━━━━━━━━━━━━━━━━━━━━━━━━━━";

        Component result = Component.empty()
                .append(Component.text(line).color(NamedTextColor.YELLOW))
                .append(Component.newline())
                .append(lang.msgNoPrefix(sender, LangKey.NOTIFY_PAY_QUEUED_TITLE)
                        .color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.BOLD, true))
                .append(Component.newline())
                .append(lang.msgNoPrefix(sender, LangKey.NOTIFY_PAY_QUEUED_LINE_TARGET,
                        Map.of("target", targetName)))
                .append(Component.newline())
                .append(lang.msgNoPrefix(sender, LangKey.NOTIFY_PAY_QUEUED_LINE_AMOUNT, Map.of(
                        "amount", amount.toPlainString(),
                        "symbol", config.economy.currencySymbol)))
                .append(Component.newline());

        if (tax.signum() > 0) {
            result = result
                    .append(lang.msgNoPrefix(sender, LangKey.NOTIFY_PAY_QUEUED_LINE_TAX, Map.of(
                                    "tax", tax.toPlainString(),
                                    "symbol", config.economy.currencySymbol))
                            .color(NamedTextColor.RED))
                    .append(Component.newline());
        }

        result = result
                .append(lang.msgNoPrefix(sender, LangKey.NOTIFY_PAY_QUEUED_LINE_DELIVER, Map.of(
                                "amount", netAmount.toPlainString(),
                                "symbol", config.economy.currencySymbol))
                        .color(NamedTextColor.GREEN))
                .append(Component.newline())
                .append(Component.text(line).color(NamedTextColor.YELLOW));

        sender.sendMessage(result);
        sender.sendActionBar(lang.msgNoPrefix(sender, LangKey.NOTIFY_PAY_ACTION_QUEUED,
                Map.of("target", targetName)).color(NamedTextColor.YELLOW));
    }

    // ============ Офлайн-платёж доставлен (получатель) ============

    public void offlinePaymentDelivered(Player target, String senderName,
                                        BigDecimal amount, int totalCount, BigDecimal totalAmount) {
        target.showTitle(Title.title(
                lang.msgNoPrefix(target, LangKey.NOTIFY_PAY_DELIVERED_TITLE)
                        .color(NamedTextColor.GOLD)
                        .decoration(TextDecoration.BOLD, true),
                lang.msgNoPrefix(target, LangKey.NOTIFY_PAY_DELIVERED_SUBTITLE, Map.of(
                                "amount", amount.toPlainString(),
                                "sender", senderName,
                                "symbol", config.economy.currencySymbol))
                        .color(NamedTextColor.GRAY),
                Title.Times.times(
                        Duration.ofMillis(200),
                        Duration.ofSeconds(2),
                        Duration.ofMillis(500))
        ));

        target.sendActionBar(lang.msgNoPrefix(target, LangKey.NOTIFY_PAY_ACTION_RECEIVED, Map.of(
                "amount", amount.toPlainString(),
                "symbol", config.economy.currencySymbol
        )).color(NamedTextColor.GREEN));

        target.sendMessage(buildReceipt(
                target,
                lang.msgNoPrefix(target, LangKey.NOTIFY_PAY_DELIVERED_TITLE),
                lang.msgNoPrefix(target, LangKey.NOTIFY_PAY_LABEL_SENDER,
                        Map.of("sender", senderName)),
                senderName,
                amount, BigDecimal.ZERO, amount,
                NamedTextColor.GOLD
        ));

        if (totalCount > 1) {
            target.sendMessage(lang.msgNoPrefix(target, LangKey.NOTIFY_PAY_DELIVERED_SUMMARY, Map.of(
                    "count", String.valueOf(totalCount),
                    "total", totalAmount.toPlainString(),
                    "symbol", config.economy.currencySymbol
            )).color(NamedTextColor.GRAY));
        }
    }

    // ============ Баланс ============

    public void showSelfBalance(Player player, BigDecimal balance) {
        String line = "━━━━━━━━━━━━━━━━━━━━━━━━━━━";

        Component msg = Component.empty()
                .append(Component.text(line).color(NamedTextColor.GOLD))
                .append(Component.newline())
                .append(lang.msgNoPrefix(player, LangKey.NOTIFY_BALANCE_SELF_TITLE)
                        .color(NamedTextColor.GOLD)
                        .decoration(TextDecoration.BOLD, true))
                .append(Component.newline())
                .append(Component.text("  "))
                .append(Component.text(balance.toPlainString() + " " + config.economy.currencySymbol)
                        .color(NamedTextColor.GREEN)
                        .decoration(TextDecoration.BOLD, true)
                        .hoverEvent(HoverEvent.showText(
                                lang.msgNoPrefix(player, LangKey.NOTIFY_BALANCE_SELF_HOVER))))
                .append(Component.newline())
                .append(Component.text(line).color(NamedTextColor.GOLD));

        player.sendMessage(msg);
        player.sendActionBar(lang.msgNoPrefix(player, LangKey.NOTIFY_BALANCE_ACTION,
                        Map.of("amount", balance.toPlainString(),
                                "symbol", config.economy.currencySymbol))
                .color(NamedTextColor.GREEN));
    }

    public void showOtherBalance(Player viewer, String targetName, BigDecimal balance) {
        String line = "━━━━━━━━━━━━━━━━━━━━━━━━━━━";

        Component msg = Component.empty()
                .append(Component.text(line).color(NamedTextColor.AQUA))
                .append(Component.newline())
                .append(lang.msgNoPrefix(viewer, LangKey.NOTIFY_BALANCE_OTHER_TITLE)
                        .color(NamedTextColor.AQUA)
                        .decoration(TextDecoration.BOLD, true))
                .append(Component.newline())
                .append(Component.text("  "))
                .append(Component.text(targetName)
                        .color(NamedTextColor.YELLOW)
                        .hoverEvent(HoverEvent.showText(
                                lang.msgNoPrefix(viewer, LangKey.NOTIFY_BALANCE_OTHER_HOVER)))
                        .clickEvent(ClickEvent.suggestCommand("/pay " + targetName + " ")))
                .append(Component.newline())
                .append(Component.text("  "))
                .append(Component.text(balance.toPlainString() + " " + config.economy.currencySymbol)
                        .color(NamedTextColor.GREEN)
                        .decoration(TextDecoration.BOLD, true))
                .append(Component.newline())
                .append(Component.text(line).color(NamedTextColor.AQUA));

        viewer.sendMessage(msg);
    }

    // ============ Вспомогательный метод — рамка чека ============

    private Component buildReceipt(Player receiver,
                                   Component title,
                                   Component label,
                                   String otherPlayerName,
                                   BigDecimal amount,
                                   BigDecimal tax,
                                   BigDecimal netAmount,
                                   NamedTextColor accent) {
        String line = "━━━━━━━━━━━━━━━━━━━━━━━━━━━";

        Component result = Component.empty()
                .append(Component.text(line).color(accent))
                .append(Component.newline())
                .append(Component.text("  "))
                .append(title.color(accent).decoration(TextDecoration.BOLD, true))
                .append(Component.newline())
                .append(Component.text("  "))
                .append(label.color(NamedTextColor.GRAY))
                .append(Component.text(": ").color(NamedTextColor.GRAY))
                .append(Component.text(otherPlayerName)
                        .color(NamedTextColor.YELLOW)
                        .hoverEvent(HoverEvent.showText(
                                lang.msgNoPrefix(receiver, LangKey.NOTIFY_RECEIPT_HOVER_NAME)))
                        .clickEvent(ClickEvent.runCommand("/balance " + otherPlayerName)))
                .append(Component.newline())
                .append(lang.msgNoPrefix(receiver, LangKey.NOTIFY_RECEIPT_LINE_AMOUNT, Map.of(
                        "amount", amount.toPlainString(),
                        "symbol", config.economy.currencySymbol)))
                .append(Component.newline());

        if (tax.signum() > 0) {
            result = result
                    .append(lang.msgNoPrefix(receiver, LangKey.NOTIFY_RECEIPT_LINE_TAX, Map.of(
                                    "tax", tax.toPlainString(),
                                    "symbol", config.economy.currencySymbol))
                            .color(NamedTextColor.RED)
                            .hoverEvent(HoverEvent.showText(
                                    lang.msgNoPrefix(receiver, LangKey.NOTIFY_RECEIPT_HOVER_TAX,
                                            Map.of("percent",
                                                    BigDecimal.valueOf(config.pay.taxPercent).toPlainString())))))
                    .append(Component.newline());
        }

        result = result
                .append(lang.msgNoPrefix(receiver, LangKey.NOTIFY_RECEIPT_LINE_TOTAL, Map.of(
                                "amount", netAmount.toPlainString(),
                                "symbol", config.economy.currencySymbol))
                        .color(NamedTextColor.GREEN)
                        .decoration(TextDecoration.BOLD, true))
                .append(Component.newline())
                .append(Component.text(line).color(accent));

        return result;
    }
}