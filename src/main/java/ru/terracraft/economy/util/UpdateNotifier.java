package ru.terracraft.economy.util;

import com.github.ezplugins.updater.UpdateChecker;
import org.bukkit.plugin.java.JavaPlugin;
import ru.terracraft.economy.config.PluginConfig;

import java.util.logging.Logger;

public class UpdateNotifier {

    private static final String GITHUB_OWNER = "luci4ka";
    private static final String GITHUB_REPO  = "TerrCraftEconomy";

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final Logger logger;

    public UpdateNotifier(JavaPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.logger = plugin.getLogger();
    }

    public void checkAsync() {
        if (!config.updates.check) {
            logger.info("Проверка обновлений отключена в конфиге.");
            return;
        }

        String currentVersion = plugin.getPluginMeta().getVersion();

        UpdateChecker checker = UpdateChecker
                .builder(GITHUB_OWNER, GITHUB_REPO, currentVersion)
                .build();

        checker.checkNowAsync().thenAccept(result -> {
            if (result.hasError()) {
                String msg = result.getError()
                        .map(Throwable::getMessage)
                        .orElse("неизвестная ошибка");
                logger.warning("Не удалось проверить обновления: " + msg);
                return;
            }

            if (result.isUpdateAvailable()) {
                String latest = result.getLatestVersion().orElse("?");
                String releaseUrl = result.getReleaseUrl().orElse(
                        "https://github.com/" + GITHUB_OWNER + "/" + GITHUB_REPO + "/releases/latest");

                logger.info("=================================================");
                logger.info(" Доступно обновление TerrEconomy!");
                logger.info(" Текущая версия: " + currentVersion);
                logger.info(" Новая версия:   " + latest);
                logger.info(" Скачать: " + releaseUrl);
                logger.info("=================================================");
            } else {
                logger.info("TerrEconomy обновлён до последней версии (" + currentVersion + ").");
            }
        }).exceptionally(ex -> {
            logger.warning("Не удалось проверить обновления: " + ex.getMessage());
            return null;
        });
    }
}