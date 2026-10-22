package ru.terracraft.economy.lang;

import de.exlll.configlib.Configuration;
import de.exlll.configlib.YamlConfigurationProperties;
import de.exlll.configlib.YamlConfigurationStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import ru.terracraft.economy.config.PluginConfig;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class LangManager {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final String defaultLocale;

    private final Map<String, Map<String, String>> localeCache = new HashMap<>();
    private final Map<String, String> prefixCache = new HashMap<>();

    public LangManager(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.defaultLocale = config.lang.locale;
        reload();
    }

    public void reload() {
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
            plugin.saveResource(fileName, false);
        }

        YamlConfigurationProperties props = YamlConfigurationProperties.newBuilder().build();
        YamlConfigurationStore<LangFile> store =
                new YamlConfigurationStore<>(LangFile.class, props);

        LangFile langFileObj = store.load(langFile.toPath());

        prefixCache.put(locale,
                langFileObj.prefix != null
                        ? langFileObj.prefix
                        : "<dark_gray>[<gold>TerrEconomy<dark_gray>] <reset>");

        Map<String, String> map = new HashMap<>(langFileObj.messages);
        localeCache.put(locale, map);
    }

    private String resolveLocale(Player player) {
        if (player == null) return defaultLocale;

        Locale locale = player.locale();
        String code = locale.getLanguage() + "_" + locale.getCountry();

        if (localeCache.containsKey(code)) {
            return code;
        }
        String langOnly = locale.getLanguage() + "_"
                + locale.getLanguage().toUpperCase(Locale.ROOT);
        if (localeCache.containsKey(langOnly)) {
            return langOnly;
        }
        return defaultLocale;
    }

    public String raw(Player player, LangKey key, Map<String, String> placeholders) {
        String locale = resolveLocale(player);
        Map<String, String> map = localeCache.getOrDefault(locale, Map.of());
        String msg = map.getOrDefault(key.key(),
                "<red>Missing lang key: " + key.key());
        return applyPlaceholders(msg, placeholders);
    }

    public String raw(Player player, LangKey key) {
        return raw(player, key, null);
    }

    public Component msgNoPrefix(Player player, LangKey key, Map<String, String> placeholders) {
        return MM.deserialize(raw(player, key, placeholders));
    }

    public Component msgNoPrefix(Player player, LangKey key) {
        return msgNoPrefix(player, key, null);
    }

    public Component msg(Player player, LangKey key, Map<String, String> placeholders) {
        String locale = resolveLocale(player);
        String prefix = prefixCache.getOrDefault(locale, "");
        return MM.deserialize(prefix + raw(player, key, placeholders));
    }

    public Component msg(Player player, LangKey key) {
        return msg(player, key, null);
    }

    public Component msg(LangKey key, Map<String, String> placeholders) {
        String prefix = prefixCache.getOrDefault(defaultLocale, "");
        Map<String, String> map = localeCache.getOrDefault(defaultLocale, Map.of());
        String msg = map.getOrDefault(key.key(),
                "<red>Missing lang key: " + key.key());
        return MM.deserialize(prefix + applyPlaceholders(msg, placeholders));
    }

    public Component msg(LangKey key) {
        return msg(key, null);
    }

    public Component msgNoPrefix(LangKey key, Map<String, String> placeholders) {
        return MM.deserialize(rawRaw(defaultLocale, key, placeholders));
    }

    private String rawRaw(String locale, LangKey key, Map<String, String> placeholders) {
        Map<String, String> map = localeCache.getOrDefault(locale, Map.of());
        String msg = map.getOrDefault(key.key(),
                "<red>Missing lang key: " + key.key());
        return applyPlaceholders(msg, placeholders);
    }

    public String getPrefix() {
        return prefixCache.getOrDefault(defaultLocale, "");
    }

    private String applyPlaceholders(String msg, Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) return msg;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            msg = msg.replace("{" + e.getKey() + "}", e.getValue());
        }
        return msg;
    }

    /** Чистая строка суффикса сокращения (без MiniMessage). */
    public String abbrevSuffix(LangKey key) {
        Map<String, String> map = localeCache.getOrDefault(defaultLocale, Map.of());
        return map.getOrDefault(key.key(), key.key());
    }

    @Configuration
    public static final class LangFile {
        public String prefix = "<dark_gray>[<gold>TerrEconomy<dark_gray>] <reset>";
        public Map<String, String> messages = new HashMap<>();
    }
}