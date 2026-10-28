package ru.terracraft.economy.lang;

import de.exlll.configlib.Configuration;
import de.exlll.configlib.YamlConfigurationProperties;
import de.exlll.configlib.YamlConfigurationStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import ru.terracraft.economy.config.PluginConfig;

import java.io.File;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class LangManager {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Встроенный fallback для критичных ключей — на случай, если locale-файл не загрузился. */
    private static final Map<String, String> FALLBACK = Map.of(
            "general.no-permission", "<red>You don't have permission.",
            "general.player-not-found", "<red>Player <yellow><target></yellow> not found.",
            "general.player-never-joined", "<red>Player <yellow><target></yellow> has never joined the server.",
            "general.invalid-amount", "<red>Invalid amount.",
            "general.negative-amount", "<red>Amount must be positive.",
            "general.self-action", "<red>You cannot perform this action on yourself.",
            "general.reloaded", "<green>Configuration and language files reloaded.",
            "general.rate-limit", "<red>Please wait <yellow><seconds></yellow> sec.",
            "pay.insufficient-funds", "<red>Insufficient funds.",
            "pay.target-max-balance", "<red>Target has reached max balance."
    );

    private final Plugin plugin;
    private final PluginConfig config;

    private volatile String defaultLocale;

    private final Map<String, Map<String, String>> localeCache = new ConcurrentHashMap<>();
    private final Map<String, String> prefixCache = new ConcurrentHashMap<>();
    private final Map<String, CurrencyForms> currencyCache = new ConcurrentHashMap<>();

    public LangManager(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
        reload();
    }

    public void reload() {
        this.defaultLocale = config.lang.locale;
        localeCache.clear();
        prefixCache.clear();
        currencyCache.clear();

        loadLocale(defaultLocale);
        if (!defaultLocale.equals("en_US")) {
            loadLocale("en_US");
        }

        if (!localeCache.containsKey(defaultLocale)) {
            plugin.getLogger().severe(
                    "Локаль по умолчанию '" + defaultLocale + "' не загружена — "
                            + "использую встроенный fallback для критичных ключей.");
        }
    }

    private void loadLocale(String locale) {
        String fileName = "lang/" + locale + ".yml";
        File langFile = new File(plugin.getDataFolder(), fileName);

        if (!langFile.exists()) {
            langFile.getParentFile().mkdirs();
            try {
                plugin.saveResource(fileName, false);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Ресурс " + fileName + " отсутствует в jar: " + e.getMessage());
                return;
            }
        }

        YamlConfigurationProperties props = YamlConfigurationProperties.newBuilder().build();
        YamlConfigurationStore<LangFile> store =
                new YamlConfigurationStore<>(LangFile.class, props);

        try {
            LangFile langFileObj = store.load(langFile.toPath());
            prefixCache.put(locale, langFileObj.prefix != null
                    ? langFileObj.prefix
                    : "<dark_gray>[<gold>TerrEconomy<dark_gray>] <reset>");
            localeCache.put(locale,
                    langFileObj.messages != null ? new HashMap<>(langFileObj.messages) : Map.of());
            currencyCache.put(locale, new CurrencyForms(
                    langFileObj.currency.singular,
                    langFileObj.currency.pluralFew,
                    langFileObj.currency.pluralMany));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Не удалось загрузить " + fileName + ": " + e.getMessage(), e);
        }
    }

    private String resolveLocale(Player player) {
        if (player == null) return defaultLocale;

        Locale locale = player.locale();
        String code = locale.getLanguage() + "_" + locale.getCountry();
        if (localeCache.containsKey(code)) return code;

        String byLang = findLocaleByLanguage(locale.getLanguage());
        if (byLang != null) return byLang;

        return defaultLocale;
    }

    private String findLocaleByLanguage(String lang) {
        for (String key : localeCache.keySet()) {
            if (key.startsWith(lang + "_")) return key;
        }
        return null;
    }

    public String raw(Player player, LangKey key) {
        return rawFor(resolveLocale(player), key);
    }

    public String raw(Player player, LangKey key, Map<String, String> placeholders) {
        String template = rawFor(resolveLocale(player), key);
        return substitute(template, placeholders);
    }

    private String rawFor(String locale, LangKey key) {
        Map<String, String> map = localeCache.get(locale);
        if (map != null) {
            String value = map.get(key.key());
            if (value != null) return value;
        }
        String fallback = FALLBACK.get(key.key());
        if (fallback != null) return fallback;
        return "<red>Missing lang key: " + key.key();
    }

    public Component msgNoPrefix(Player player, LangKey key, Map<String, String> placeholders) {
        return deserialize(rawFor(resolveLocale(player), key), placeholders);
    }

    public Component msgNoPrefix(Player player, LangKey key) {
        return msgNoPrefix(player, key, null);
    }

    public Component msg(Player player, LangKey key, Map<String, String> placeholders) {
        String locale = resolveLocale(player);
        String prefix = prefixCache.getOrDefault(locale, "");
        return deserialize(prefix + rawFor(locale, key), placeholders);
    }

    public Component msg(Player player, LangKey key) {
        return msg(player, key, null);
    }

    public Component msg(LangKey key, Map<String, String> placeholders) {
        String prefix = prefixCache.getOrDefault(defaultLocale, "");
        return deserialize(prefix + rawFor(defaultLocale, key), placeholders);
    }

    public Component msg(LangKey key) {
        return msg(key, null);
    }

    public Component msgNoPrefix(LangKey key, Map<String, String> placeholders) {
        return deserialize(rawFor(defaultLocale, key), placeholders);
    }

    public String getPrefix() {
        return prefixCache.getOrDefault(defaultLocale, "");
    }

    public String abbrevSuffix(LangKey key) {
        Map<String, String> map = localeCache.getOrDefault(defaultLocale, Map.of());
        return map.getOrDefault(key.key(), key.key());
    }

    public String abbrevSuffix(Player player, LangKey key) {
        String locale = resolveLocale(player);
        Map<String, String> map = localeCache.getOrDefault(locale, Map.of());
        return map.getOrDefault(key.key(), key.key());
    }

    // ============ Плюрализация валюты ============

    /**
     * Возвращает название валюты в правильной форме для числа.
     * <ul>
     *   <li>{@code ru_*} — 3 формы (singular/few/many) по русским правилам;</li>
     *   <li>остальные — 2 формы (singular для 1, pluralMany для всего остального).</li>
     * </ul>
     */
    public String currencyFor(Player player, BigDecimal amount) {
        String locale = resolveLocale(player);
        return currencyFor(locale, amount);
    }

    public String currencyFor(String locale, BigDecimal amount) {
        CurrencyForms forms = currencyCache.get(locale);
        if (forms == null) forms = currencyCache.get(defaultLocale);
        if (forms == null) return "";

        long n = amount.abs().setScale(0, java.math.RoundingMode.HALF_UP).longValue();
        long mod100 = n % 100;
        long mod10 = n % 10;

        if (locale.startsWith("ru")) {
            if (mod100 >= 11 && mod100 <= 14) return forms.pluralMany;
            if (mod10 == 1) return forms.singular;
            if (mod10 >= 2 && mod10 <= 4) return forms.pluralFew;
            return forms.pluralMany;
        }
        // en / default: 2 формы
        return n == 1 ? forms.singular : forms.pluralMany;
    }

    /**
     * Форматирует сумму с плюрализованным названием валюты.
     * Не включает символ {@code $} — только число + название.
     * Пример: {@code "100.00 монет"}, {@code "1 монета"}.
     */
    public Component formatCurrency(Player player, BigDecimal amount) {
        String currency = currencyFor(player, amount);
        String number = amount.toPlainString();
        return Component.text(number + " " + currency);
    }

    /**
     * То же, но с локализованным цветом/форматом из YAML:
     * ключ {@code currency.format} — {@code "<white><amount> <currency>"}.
     * Если ключа нет — fallback на {@link #formatCurrency(Player, BigDecimal)}.
     */
    public Component formatCurrencyStyled(Player player, BigDecimal amount) {
        String template = rawFor(resolveLocale(player), LangKey.CURRENCY_FORMAT);
        if (template.startsWith("<red>Missing lang key")) {
            return formatCurrency(player, amount);
        }
        return deserialize(template, Map.of(
                "amount", amount.toPlainString(),
                "currency", currencyFor(player, amount)
        ));
    }

    // ============ Внутреннее ============

    private String substitute(String template, Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) return template;
        String result = template;
        for (var e : placeholders.entrySet()) {
            result = result.replace("<" + e.getKey() + ">", e.getValue());
        }
        return result;
    }

    private Component deserialize(String template, Map<String, String> placeholders) {
        try {
            if (placeholders == null || placeholders.isEmpty()) {
                return MM.deserialize(template);
            }
            TagResolver.Builder builder = TagResolver.builder();
            for (var e : placeholders.entrySet()) {
                builder.resolver(Placeholder.unparsed(e.getKey(), e.getValue()));
            }
            return MM.deserialize(template, builder.build());
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка MiniMessage: " + e.getMessage()
                    + " в строке: " + template);
            try {
                return Component.text(MM.stripTags(template));
            } catch (Exception ignored) {
                return Component.text(template);
            }
        }
    }

    private record CurrencyForms(String singular, String pluralFew, String pluralMany) {}

    @Configuration
    public static final class LangFile {
        public String prefix = "<dark_gray>[<gold>TerrEconomy<dark_gray>] <reset>";
        public Map<String, String> messages = new HashMap<>();
        public CurrencyConfig currency = new CurrencyConfig();

        @Configuration
        public static final class CurrencyConfig {
            public String singular = "coin";
            public String pluralFew = "coins";
            public String pluralMany = "coins";
        }
    }
}