package ru.net.terracraft.eco.config;

import de.exlll.configlib.YamlConfigurationProperties;
import de.exlll.configlib.YamlConfigurationStore;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.nio.file.Path;

public class ConfigLoader {

    private final Plugin plugin;
    private final YamlConfigurationStore<PluginConfig> store;
    private final Path configPath;
    private PluginConfig config;

    public ConfigLoader(Plugin plugin) {
        this.plugin = plugin;
        this.configPath = new File(plugin.getDataFolder(), "config.yml").toPath();

        YamlConfigurationProperties props = YamlConfigurationProperties.newBuilder()
                .header("TerrEconomy — конфигурация\nДокументация: terracraft.net.ru")
                .build();

        this.store = new YamlConfigurationStore<>(PluginConfig.class, props);
    }

    /**
     * Загружает конфиг из файла. Если файла нет — создаёт с дефолтами.
     * Если есть новые поля — добавляет их, сохраняя пользовательские значения.
     */
    public void load() {
        this.config = store.update(configPath);
    }

    /**
     * Сохраняет текущий конфиг обратно в файл.
     * Нужно после изменений в коде (например, через /terrEco reload).
     */
    public void save() {
        if (config != null) {
            store.save(config, configPath);
        }
    }

    public PluginConfig get() {
        return config;
    }
}