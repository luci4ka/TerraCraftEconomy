package ru.terracraft.economy.util;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;
import ru.terracraft.economy.config.PluginConfig;
import ru.terracraft.economy.lang.LangKey;
import ru.terracraft.economy.lang.LangManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

public class NotificationManager {

    /** Длина разделительной линии в чате (символов). */
    private static final int LINE_LENGTH = 40;

    /** Отступ для строк внутри блока. */
    private static final String INDENT = "  ";

    /** Текстовые маркеры вместо эмодзи. */
    private static final String MARKER = "▸ ";
    private static final String BULLET = "• ";

    /** Время показа Title (fadeIn, stay, fadeOut). */
    private static final Title.Times TITLE_TIMES = Title.Times.times(
            Duration.ofMillis(200),
            Duration.ofSeconds(2),
            Duration.ofMillis(500));

    private final PluginConfig config;
    private final LangManager lang;

    public NotificationManager(PluginConfig config, LangManager lang) {
        this.config = config;
        this.lang = lang;
    }

    // ============ Хелперы сборки ============

    private Component line(NamedTextColor color) {
        return Component.text("━".repeat(LINE_LENGTH)).color(color);
    }

    /** Оборачивает набор строк в рамку с линиями сверху и снизу. */
    private Component block(NamedTextColor accent, Component... lines) {
        Component result = Component.empty().append(line(accent));
        for (Component line : lines) {
            result = result.append(Component.newline()).append(line);
        }
        return result.append(Component.newline()).append(line(accent));
    }

    /** Добавляет отступ в начало строки. */
    private Component indented(Component content) {
        return Component.text(INDENT).append(content);
    }

    /** Строка «маркер + подпись + значение» с отступом. */
    private Component row(NamedTextColor markerColor, Component label, Component value) {
        return indented(
                Component.text(MARKER).color(markerColor)
                        .append(label)
                        .append(Component.text(": ").color(NamedTextColor.DARK_GRAY))
                        .append(value)
        );
    }

    /** Воспроизводит звук, если он включён в конфиге. */
    private void playSound(Player player, PluginConfig.NotifySection.SoundSection cfg) {
        if (!cfg.enabled) return;
        Sound sound = Sound.sound(
                Key.key(cfg.key),
                Sound.Source.PLAYER,
                cfg.volume,
                cfg.pitch
        );
        // Emitter.self() обязателен для Adventure: звук следует за игроком.
        player.playSound(sound, Sound.Emitter.self());
    }

    // ============ Перевод выполнен (отправитель) ============

    public void paymentSent(Player sender, String targetName,
                            BigDecimal amount, BigDecimal tax, BigDecimal netAmount) {
        sender.sendMessage(buildReceipt(
                sender,
                LangKey.NOTIFY_PAY_SENT_TITLE,
                LangKey.NOTIFY_PAY_LABEL_TARGET,
                targetName,
                amount, tax, netAmount,
                NamedTextColor.GOLD,
                true  // отправитель видит налог
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
                TITLE_TIMES
        ));

        target.sendActionBar(lang.msgNoPrefix(target, LangKey.NOTIFY_PAY_ACTION_RECEIVED, Map.of(
                "amount", netAmount.toPlainString(),
                "symbol", config.economy.currencySymbol
        )).color(NamedTextColor.GREEN));

        target.sendMessage(buildReceipt(
                target,
                LangKey.NOTIFY_PAY_RECEIVED_TITLE,
                LangKey.NOTIFY_PAY_LABEL_SENDER,
                senderName,
                amount, tax, netAmount,
                NamedTextColor.GREEN,
                false  // получатель не платил налог — не показываем
        ));

        playSound(target, config.notify.receiveSound);
    }

    // ============ Офлайн-перевод (отправитель) ============

    public void paymentQueued(Player sender, String targetName,
                              BigDecimal amount, BigDecimal tax, BigDecimal netAmount) {
        Component result = block(NamedTextColor.YELLOW,
                indented(lang.msgNoPrefix(sender, LangKey.NOTIFY_PAY_QUEUED_TITLE)
                        .color(NamedTextColor.YELLOW)
                        .decoration(TextDecoration.BOLD, true)),
                row(NamedTextColor.YELLOW,
                        lang.msgNoPrefix(sender, LangKey.NOTIFY_PAY_QUEUED_LINE_TARGET).color(NamedTextColor.GRAY),
                        Component.text(targetName).color(NamedTextColor.YELLOW)),
                row(NamedTextColor.YELLOW,
                        lang.msgNoPrefix(sender, LangKey.NOTIFY_PAY_QUEUED_LINE_AMOUNT).color(NamedTextColor.GRAY),
                        Component.text(amount.toPlainString() + " " + config.economy.currencySymbol)
                                .color(NamedTextColor.WHITE)),
                tax.signum() > 0
                        ? row(NamedTextColor.RED,
                        lang.msgNoPrefix(sender, LangKey.NOTIFY_PAY_QUEUED_LINE_TAX).color(NamedTextColor.GRAY),
                        Component.text("-" + tax.toPlainString() + " " + config.economy.currencySymbol)
                                .color(NamedTextColor.RED))
                        : null,
                row(NamedTextColor.GREEN,
                        lang.msgNoPrefix(sender, LangKey.NOTIFY_PAY_QUEUED_LINE_DELIVER).color(NamedTextColor.GRAY),
                        Component.text(netAmount.toPlainString() + " " + config.economy.currencySymbol)
                                .color(NamedTextColor.GREEN)
                                .decoration(TextDecoration.BOLD, true))
        );

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
                TITLE_TIMES
        ));

        target.sendActionBar(lang.msgNoPrefix(target, LangKey.NOTIFY_PAY_ACTION_RECEIVED, Map.of(
                "amount", amount.toPlainString(),
                "symbol", config.economy.currencySymbol
        )).color(NamedTextColor.GREEN));

        target.sendMessage(buildReceipt(
                target,
                LangKey.NOTIFY_PAY_DELIVERED_TITLE,
                LangKey.NOTIFY_PAY_LABEL_SENDER,
                senderName,
                amount, BigDecimal.ZERO, amount,
                NamedTextColor.GOLD,
                false
        ));

        if (totalCount > 1) {
            target.sendMessage(indented(lang.msgNoPrefix(target, LangKey.NOTIFY_PAY_DELIVERED_SUMMARY, Map.of(
                    "count", String.valueOf(totalCount),
                    "total", totalAmount.toPlainString(),
                    "symbol", config.economy.currencySymbol
            )).color(NamedTextColor.GRAY)));
        }

        playSound(target, config.notify.offlineDeliveredSound);
    }

    // ============ Баланс ============

    public void showSelfBalance(Player player, BigDecimal balance) {
        player.sendMessage(block(NamedTextColor.GOLD,
                indented(lang.msgNoPrefix(player, LangKey.NOTIFY_BALANCE_SELF_TITLE)
                        .color(NamedTextColor.GOLD)
                        .decoration(TextDecoration.BOLD, true)),
                indented(Component.text(balance.toPlainString() + " " + config.economy.currencySymbol)
                        .color(NamedTextColor.GREEN)
                        .decoration(TextDecoration.BOLD, true)
                        .hoverEvent(HoverEvent.showText(
                                lang.msgNoPrefix(player, LangKey.NOTIFY_BALANCE_SELF_HOVER)))
                        .clickEvent(ClickEvent.suggestCommand("/pay ")))
        ));

        player.sendActionBar(lang.msgNoPrefix(player, LangKey.NOTIFY_BALANCE_ACTION,
                        Map.of("amount", balance.toPlainString(),
                                "symbol", config.economy.currencySymbol))
                .color(NamedTextColor.GREEN));
    }

    public void showOtherBalance(Player viewer, String targetName, BigDecimal balance) {
        viewer.sendMessage(block(NamedTextColor.AQUA,
                indented(lang.msgNoPrefix(viewer, LangKey.NOTIFY_BALANCE_OTHER_TITLE)
                        .color(NamedTextColor.AQUA)
                        .decoration(TextDecoration.BOLD, true)),
                row(NamedTextColor.AQUA,
                        lang.msgNoPrefix(viewer, LangKey.NOTIFY_BALANCE_OTHER_LABEL).color(NamedTextColor.GRAY),
                        Component.text(targetName)
                                .color(NamedTextColor.YELLOW)
                                .hoverEvent(HoverEvent.showText(
                                        lang.msgNoPrefix(viewer, LangKey.NOTIFY_BALANCE_OTHER_HOVER)))
                                .clickEvent(ClickEvent.suggestCommand("/pay " + targetName + " "))),
                indented(Component.text(balance.toPlainString() + " " + config.economy.currencySymbol)
                        .color(NamedTextColor.GREEN)
                        .decoration(TextDecoration.BOLD, true))
        ));

        viewer.sendActionBar(lang.msgNoPrefix(viewer, LangKey.NOTIFY_BALANCE_ACTION,
                        Map.of("amount", balance.toPlainString(),
                                "symbol", config.economy.currencySymbol))
                .color(NamedTextColor.AQUA));
    }

    // ============ Чек ============

    /**
     * @param showTax true — показать блок налога (для отправителя);
     *                false — скрыть (для получателя, который налог не платил).
     */
    private Component buildReceipt(Player receiver,
                                   LangKey titleKey,
                                   LangKey labelKey,
                                   String otherPlayerName,
                                   BigDecimal amount,
                                   BigDecimal tax,
                                   BigDecimal netAmount,
                                   NamedTextColor accent,
                                   boolean showTax) {
        Component title = lang.msgNoPrefix(receiver, titleKey)
                .color(accent)
                .decoration(TextDecoration.BOLD, true);

        Component label = lang.msgNoPrefix(receiver, labelKey)
                .color(NamedTextColor.GRAY);

        Component name = Component.text(otherPlayerName)
                .color(NamedTextColor.YELLOW)
                .hoverEvent(HoverEvent.showText(
                        lang.msgNoPrefix(receiver, LangKey.NOTIFY_RECEIPT_HOVER_NAME)))
                .clickEvent(ClickEvent.runCommand("/balance " + otherPlayerName));

        Component amountRow = row(accent,
                lang.msgNoPrefix(receiver, LangKey.NOTIFY_RECEIPT_LINE_AMOUNT).color(NamedTextColor.GRAY),
                Component.text(amount.toPlainString() + " " + config.economy.currencySymbol)
                        .color(NamedTextColor.WHITE));

        Component taxRow = null;
        if (showTax && tax.signum() > 0) {
            taxRow = row(NamedTextColor.RED,
                    lang.msgNoPrefix(receiver, LangKey.NOTIFY_RECEIPT_LINE_TAX).color(NamedTextColor.GRAY),
                    Component.text("-" + tax.toPlainString() + " " + config.economy.currencySymbol)
                            .color(NamedTextColor.RED))
                    .hoverEvent(HoverEvent.showText(
                            lang.msgNoPrefix(receiver, LangKey.NOTIFY_RECEIPT_HOVER_TAX,
                                    Map.of("percent",
                                            BigDecimal.valueOf(config.pay.taxPercent).toPlainString()))));
        }

        Component totalRow = row(NamedTextColor.GREEN,
                lang.msgNoPrefix(receiver, LangKey.NOTIFY_RECEIPT_LINE_TOTAL).color(NamedTextColor.GRAY),
                Component.text(netAmount.toPlainString() + " " + config.economy.currencySymbol)
                        .color(NamedTextColor.GREEN)
                        .decoration(TextDecoration.BOLD, true));

        if (taxRow != null) {
            return block(accent,
                    indented(title),
                    indented(Component.text(MARKER).color(accent).append(label).append(Component.text(": ").color(NamedTextColor.DARK_GRAY)).append(name)),
                    amountRow,
                    taxRow,
                    totalRow);
        } else {
            return block(accent,
                    indented(title),
                    indented(Component.text(MARKER).color(accent).append(label).append(Component.text(": ").color(NamedTextColor.DARK_GRAY)).append(name)),
                    amountRow,
                    totalRow);
        }
    }
}