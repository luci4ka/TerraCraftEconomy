package ru.terracraft.economy.lang;

import de.exlll.configlib.Configuration;
import de.exlll.configlib.YamlConfigurationProperties;
import de.exlll.configlib.YamlConfigurationStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import ru.terracraft.economy.config.PluginConfig;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class LangManager {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final PluginConfig config;

    /** Пересчитывается в {@link #reload()} — не final, чтобы /eco reload подхватывал смену локали. */
    private volatile String defaultLocale;

    private final Map<String, Map<String, String>> localeCache = new ConcurrentHashMap<>();
    private final Map<String, String> prefixCache = new ConcurrentHashMap<>();

    public LangManager(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
        reload();
    }

    public void reload() {
        this.defaultLocale = config.lang.locale;
        localeCache.clear();
        prefixCache.clear();

        loadLocale(defaultLocale);
        if (!defaultLocale.equals("en_US")) {
            loadLocale("en_US");
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

        // Fallback по языку: en_GB → en_US, ru_UA → ru_RU и т.п.
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

    /**
     * Возвращает шаблон строки без подстановки. Плейсхолдеры в YAML
     * должны быть в формате {@code <key>} (как {@code <arg:0>} в гайде).
     * Подстановка — в {@link #deserialize(String, Map)} через
     * {@link Placeholder#unparsed(String, String)} — это экранирует
     * пользовательский ввод и не даёт интерпретировать его как MiniMessage.
     */
    public String raw(Player player, LangKey key) {
        return rawFor(resolveLocale(player), key);
    }

    private String rawFor(String locale, LangKey key) {
        Map<String, String> map = localeCache.getOrDefault(locale, Map.of());
        return map.getOrDefault(key.key(), "<red>Missing lang key: " + key.key());
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

    /** Чистая строка суффикса сокращения (без MiniMessage). */
    public String abbrevSuffix(LangKey key) {
        Map<String, String> map = localeCache.getOrDefault(defaultLocale, Map.of());
        return map.getOrDefault(key.key(), key.key());
    }

    /**
     * Десериализация MiniMessage с плейсхолдерами через {@link Placeholder#unparsed}:
     * значения не парсятся как разметка (защита от инъекции тегов в никах и т.п.).
     * При ошибке разметки — fallback на plain text (теги стрипаются).
     */
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
                return Component.text(PlainTextComponentSerializer.plainText()
                        .serialize(Component.text(template)));
            }
        }
    }

    @Configuration
    public static final class LangFile {
        public String prefix = "<dark_gray>[<gold>TerrEconomy<dark_gray>] <reset>";
        public Map<String, String> messages = new HashMap<>();
    }
}