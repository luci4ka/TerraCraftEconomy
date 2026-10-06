package ru.net.terracraft.eco.config;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;

import java.math.BigDecimal;

@Configuration
public final class PluginConfig {

    @Comment("Язык сообщений: ru_RU, en_US")
    public LangSection lang = new LangSection();

    @Comment("Настройки базы данных H2")
    public DatabaseSection database = new DatabaseSection();

    @Comment("Основные настройки экономики")
    public EconomySection economy = new EconomySection();

    @Comment("Настройки переводов между игроками")
    public PaySection pay = new PaySection();

    @Comment("Проверка обновлений плагина")
    public UpdatesSection updates = new UpdatesSection();

    // ============ Вложенные секции ============

    @Configuration
    public static final class LangSection {
        @Comment("Локаль: ru_RU или en_US")
        public String locale = "ru_RU";
    }

    @Configuration
    public static final class DatabaseSection {
        @Comment("Имя файла БД (без расширения)")
        public String file = "economy";

        @Comment("Размер пула соединений HikariCP")
        public int poolSize = 5;

        @Comment("Таймаут подключения (мс)")
        public long connectionTimeout = 10000;
    }

    @Configuration
    public static final class EconomySection {
        @Comment("Стартовый баланс новых игроков")
        public BigDecimal startingBalance = BigDecimal.ZERO;

        @Comment("Максимальный баланс")
        public BigDecimal maxBalance = BigDecimal.valueOf(1_000_000_000);

        @Comment("Символ валюты")
        public String currencySymbol = "$";

        @Comment("Название валюты в единственном числе")
        public String currencyNameSingular = "монета";

        @Comment("Название валюты во множественном числе")
        public String currencyNamePlural = "монет";

        @Comment("Количество знаков после запятой")
        public int fractionalDigits = 2;

        @Comment("Интервал автосохранения (секунды)")
        public int autoSaveInterval = 60;
    }

    @Configuration
    public static final class PaySection {
        @Comment("Процент налога с перевода (0 — отключено)")
        public double taxPercent = 0.0;

        @Comment("Минимальная сумма, с которой берётся налог")
        public BigDecimal minTaxAmount = BigDecimal.ONE;

        @Comment("Максимальный налог с одной операции (0 — без ограничения)")
        public BigDecimal maxTaxAmount = BigDecimal.valueOf(1000);

        @Comment("true — налог уходит в казну сервера, false — сгорает")
        public boolean taxToServer = true;

        @Comment("Разрешить переводы офлайн-игрокам")
        public boolean allowOffline = true;
    }

    @Configuration
    public static final class UpdatesSection {
        @Comment("Проверять обновления через GitHub Releases")
        public boolean check = true;
    }
}