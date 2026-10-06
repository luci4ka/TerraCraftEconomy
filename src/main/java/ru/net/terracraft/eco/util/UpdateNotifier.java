package ru.net.terracraft.eco.util;

import com.github.ezplugins.updater.UpdateChecker;
import com.github.ezplugins.updater.UpdateResult;
import org.bukkit.plugin.java.JavaPlugin;
import ru.net.terracraft.eco.config.PluginConfig;

import java.util.logging.Logger;

/**
 * Проверка обновлений плагина через GitHub Releases.
 * Включается/выключается через config.updates.check.
 * Репозиторий owner/repo захардкожен ниже — пользователь его не настраивает.
 */
public class UpdateNotifier {

    // === Захардкоженный репозиторий GitHub ===
    // Формат: owner/repo
    private static final String GITHUB_OWNER = "luci";
    private static final String GITHUB_REPO  = "TerrEconomy";

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final Logger logger;

    public UpdateNotifier(JavaPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.logger = plugin.getLogger();
    }

    /**
     * Запускает проверку обновлений асинхронно.
     * Если проверка выключена в конфиге — молча выходит.
     */
    public void checkAsync() {
        if (!config.updates.check) {
            logger.info("Проверка обновлений отключена в конфиге.");
            return;
        }

        String currentVersion = plugin.getPluginMeta().getVersion();

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                UpdateChecker checker = UpdateChecker
                        .builder(GITHUB_OWNER, GITHUB_REPO, currentVersion)
                        .build();

                UpdateResult result = checker.checkNow();

                if (result.isUpdateAvailable()) {
                    String latest = result.getLatestVersion().orElse("?");
                    logger.info("=================================================");
                    logger.info(" Доступно обновление TerrEconomy!");
                    logger.info(" Текущая версия: " + currentVersion);
                    logger.info(" Новая версия:   " + latest);
                    logger.info(" Скачать: https://github.com/"
                            + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/latest");
                    logger.info("=================================================");
                } else {
                    logger.info("TerrEconomy обновлён до последней версии (" + currentVersion + ").");
                }
            } catch (Exception e) {
                logger.warning("Не удалось проверить обновления: " + e.getMessage());
            }
        });
    }
}