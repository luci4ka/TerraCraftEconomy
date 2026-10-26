package ru.terracraft.economy.config;

import de.exlll.configlib.YamlConfigurationProperties;
import de.exlll.configlib.YamlConfigurationStore;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.file.Path;
import java.util.logging.Level;

public class ConfigLoader {

    private final Plugin plugin;
    private final YamlConfigurationStore<PluginConfig> store;
    private final Path configPath;
    private PluginConfig config;

    public ConfigLoader(Plugin plugin) {
        this.plugin = plugin;
        this.configPath = new File(plugin.getDataFolder(), "config.yml").toPath();

        YamlConfigurationProperties props = YamlConfigurationProperties.newBuilder()
                .header("""
                        TerrEconomy — конфигурация
                        Документация: terracraft.net.ru
                        
                        Подключение к БД настраивается в plugins/MineSQL/config.yml.
                        В этом файле укажите только ID источника данных (database.sourceId).
                        """)
                .build();

        this.store = new YamlConfigurationStore<>(PluginConfig.class, props);
    }

    /**
     * Загружает конфиг. После первого вызова {@link #get()} всегда возвращает
     * один и тот же инстанс: при последующих вызовах поля копируются «на месте»,
     * чтобы держатели ссылки (EconomyCache, EconomyService и т.п.) увидели
     * обновлённые значения без пересоздания.
     */
    public void load() {
        try {
            PluginConfig fresh = store.update(configPath);
            if (config == null) {
                config = fresh;
            } else {
                copyInto(fresh, config);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE,
                    "Не удалось загрузить config.yml: " + e.getMessage(), e);
            if (config == null) {
                config = new PluginConfig();
            }
        }
    }

    public void save() {
        if (config != null) {
            try {
                store.save(config, configPath);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE,
                        "Не удалось сохранить config.yml: " + e.getMessage(), e);
            }
        }
    }

    public PluginConfig get() {
        return config;
    }

    /** Копирует все поля из src в dst. */
    private void copyInto(PluginConfig src, PluginConfig dst) {
        dst.lang = src.lang;
        dst.database = src.database;
        dst.economy = src.economy;
        dst.pay = src.pay;
        dst.notify = src.notify;
        dst.updates = src.updates;
    }
}