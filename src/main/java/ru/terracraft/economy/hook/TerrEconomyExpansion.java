package ru.terracraft.economy.hook;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.terracraft.economy.TerrEconomyPlugin;
import ru.terracraft.economy.config.PluginConfig;
import ru.terracraft.economy.economy.EconomyService;
import ru.terracraft.economy.lang.LangKey;
import ru.terracraft.economy.lang.LangManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

public class TerrEconomyExpansion extends PlaceholderExpansion {

    private final TerrEconomyPlugin plugin;
    private final EconomyService economyService;
    private final PluginConfig config;
    private final LangManager lang;

    public TerrEconomyExpansion(TerrEconomyPlugin plugin, EconomyService economyService,
                                PluginConfig config, LangManager lang) {
        this.plugin = plugin;
        this.economyService = economyService;
        this.config = config;
        this.lang = lang;
    }

    @Override public @NotNull String getIdentifier() { return "terr_economy"; }
    @Override public @NotNull String getAuthor() { return String.join(", ", plugin.getPluginMeta().getAuthors()); }
    @Override public @NotNull String getVersion() { return plugin.getPluginMeta().getVersion(); }
    @Override public boolean persist() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return null;

        BigDecimal balance = economyService.getBalance(player.getUniqueId());

        return switch (params.toLowerCase(Locale.ROOT)) {
            case "balance" -> balance.toPlainString();
            case "balance_format" -> balance
                    .setScale(config.economy.fractionalDigits, RoundingMode.HALF_UP)
                    .toPlainString();
            case "balance_format_symvol", "balance_format_symbol" -> config.economy.currencySymbol + balance
                    .setScale(config.economy.fractionalDigits, RoundingMode.HALF_UP)
                    .toPlainString();
            case "balance_short" -> abbreviate(player, balance);
            case "balance_short_symvol", "balance_short_symbol" -> config.economy.currencySymbol + abbreviate(player, balance);
            default -> null;
        };
    }

    /**
     * Сокращает сумму с суффиксом на локали игрока (для онлайн) или
     * defaultLocale (для офлайн — {@link OfflinePlayer} не знает локаль).
     */
    private String abbreviate(OfflinePlayer player, BigDecimal value) {
        if (value == null) return "0";
        BigDecimal abs = value.abs();
        String suffix;
        BigDecimal divisor;

        if (abs.compareTo(BigDecimal.valueOf(1_000_000_000)) >= 0) {
            divisor = BigDecimal.valueOf(1_000_000_000);
            suffix = suffixFor(player, LangKey.ABBREV_B);
        } else if (abs.compareTo(BigDecimal.valueOf(1_000_000)) >= 0) {
            divisor = BigDecimal.valueOf(1_000_000);
            suffix = suffixFor(player, LangKey.ABBREV_M);
        } else if (abs.compareTo(BigDecimal.valueOf(1_000)) >= 0) {
            divisor = BigDecimal.valueOf(1_000);
            suffix = suffixFor(player, LangKey.ABBREV_K);
        } else {
            return value.setScale(0, RoundingMode.HALF_UP).toPlainString();
        }

        BigDecimal result = value.divide(divisor, 1, RoundingMode.HALF_UP);
        String formatted = result.stripTrailingZeros().toPlainString();
        return formatted + suffix;
    }

    private String suffixFor(OfflinePlayer player, LangKey key) {
        if (player instanceof Player online) {
            return lang.abbrevSuffix(online, key);
        }
        return lang.abbrevSuffix(key);
    }
}