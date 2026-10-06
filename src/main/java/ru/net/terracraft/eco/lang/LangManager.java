package ru.net.terracraft.eco.lang;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import ru.net.terracraft.eco.config.PluginConfig;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class LangManager {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Локаль по умолчанию (из конфига). */
    private final String defaultLocale;

    /** Кэш загруженных локалей: locale → { key → MiniMessage-string }. */
    private final Map<String, Map<String, String>> localeCache = new HashMap<>();

    /** Кэш prefix: locale → prefix. */
    private final Map<String, String> prefixCache = new HashMap<>();

    private final Plugin plugin;

    public LangManager(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.defaultLocale = config.lang.locale;
        reload();
    }

    /** Полная перезагрузка всех локалей. */
    public void reload() {
        localeCache.clear();
        prefixCache.clear();

        // Загружаем локаль по умолчанию и английский (fallback)
        loadLocale(defaultLocale);
        if (!defaultLocale.equals("en_US")) {
            loadLocale("en_US");
        }
    }

    private void loadLocale(String locale) {
        String fileName = "lang/" + locale + ".yml";
        File langFile = new File(plugin.getDataFolder(), fileName);

        // 1. Сохраняем дефолтный файл, если нет
        if (!langFile.exists()) {
            langFile.getParentFile().mkdirs();
            plugin.saveResource(fileName, false);
        }

        // 2. Загружаем из диска
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(langFile);

        // 3. Подгружаем defaults из JAR
        InputStream defaultStream = plugin.getResource(fileName);
        if (defaultStream != null) {
            YamlConfiguration defaultCfg = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaultStream, StandardCharsets.UTF_8));
            cfg.setDefaults(defaultCfg);
        }

        // 4. Prefix
        prefixCache.put(locale, cfg.getString("prefix",
                "<dark_gray>[<gold>TerrEconomy<dark_gray>] <reset>"));

        // 5. Все ключи
        Map<String, String> map = new HashMap<>();
        for (String key : cfg.getKeys(true)) {
            if (cfg.isString(key)) {
                map.put(key, cfg.getString(key));
            }
        }
        localeCache.put(locale, map);
    }

    // ============ Определение локали игрока ============

    /** Возвращает локаль игрока, если она загружена. Иначе — дефолт. */
    private String resolveLocale(Player player) {
        if (player == null) return defaultLocale;

        Locale locale = player.locale();  // Paper: возвращает клиентскую локаль
        String code = locale.getLanguage() + "_" + locale.getCountry();
        // например, ru_RU, en_US

        if (localeCache.containsKey(code)) {
            return code;
        }
        // fallback на язык без региона: "ru"
        String langOnly = locale.getLanguage() + "_" + locale.getLanguage().toUpperCase(Locale.ROOT);
        if (localeCache.containsKey(langOnly)) {
            return langOnly;
        }
        return defaultLocale;
    }

    // ============ Получение строк ============

    /** Сырая MiniMessage-строка для локали игрока, без префикса. */
    public String raw(Player player, LangKey key, Map<String, String> placeholders) {
        String locale = resolveLocale(player);
        Map<String, String> map = localeCache.getOrDefault(locale, Map.of());
        String msg = map.getOrDefault(key.key(), "<red>Missing lang key: " + key.key());
        return applyPlaceholders(msg, placeholders);
    }

    public String raw(Player player, LangKey key) {
        return raw(player, key, null);
    }

    /** Component без префикса для локали игрока. */
    public Component msgNoPrefix(Player player, LangKey key, Map<String, String> placeholders) {
        return MM.deserialize(raw(player, key, placeholders));
    }

    public Component msgNoPrefix(Player player, LangKey key) {
        return msgNoPrefix(player, key, null);
    }

    /** Component с префиксом для локали игрока. */
    public Component msg(Player player, LangKey key, Map<String, String> placeholders) {
        String locale = resolveLocale(player);
        String prefix = prefixCache.getOrDefault(locale, "");
        return MM.deserialize(prefix + raw(player, key, placeholders));
    }

    public Component msg(Player player, LangKey key) {
        return msg(player, key, null);
    }

    // ============ Для консоли / не-игроков ============

    public Component msg(LangKey key, Map<String, String> placeholders) {
        String prefix = prefixCache.getOrDefault(defaultLocale, "");
        String locale = defaultLocale;
        Map<String, String> map = localeCache.getOrDefault(locale, Map.of());
        String msg = map.getOrDefault(key.key(), "<red>Missing lang key: " + key.key());
        return MM.deserialize(prefix + applyPlaceholders(msg, placeholders));
    }

    public Component msg(LangKey key) {
        return msg(key, null);
    }

    public Component msgNoPrefix(LangKey key, Map<String, String> placeholders) {
        String map = rawRaw(defaultLocale, key, placeholders);
        return MM.deserialize(map);
    }

    private String rawRaw(String locale, LangKey key, Map<String, String> placeholders) {
        Map<String, String> map = localeCache.getOrDefault(locale, Map.of());
        String msg = map.getOrDefault(key.key(), "<red>Missing lang key: " + key.key());
        return applyPlaceholders(msg, placeholders);
    }

    public String getPrefix() {
        return prefixCache.getOrDefault(defaultLocale, "");
    }

    // ============ Плейсхолдеры ============

    private String applyPlaceholders(String msg, Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) return msg;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            msg = msg.replace("{" + e.getKey() + "}", e.getValue());
        }
        return msg;
    }
}