package ru.terracraft.economy.config;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;

import java.math.BigDecimal;

@Configuration
public final class PluginConfig {

    @Comment("Язык сообщений: ru_RU, en_US")
    public LangSection lang = new LangSection();

    @Comment("Настройки подключения к базе данных через MineSQL")
    public DatabaseSection database = new DatabaseSection();

    @Comment("Основные настройки экономики")
    public EconomySection economy = new EconomySection();

    @Comment("Настройки переводов между игроками")
    public PaySection pay = new PaySection();

    @Comment("Уведомления: звуки и оформление")
    public NotifySection notify = new NotifySection();

    @Comment("Проверка обновлений плагина")
    public UpdatesSection updates = new UpdatesSection();

    @Comment("Ограничение частоты команд (антиспам)")
    public RateLimitSection rateLimit = new RateLimitSection();

    @Configuration
    public static final class LangSection {
        @Comment("Локаль: ru_RU или en_US")
        public String locale = "ru_RU";
    }

    @Configuration
    public static final class DatabaseSection {
        @Comment("""
                ID источника данных в plugins/MineSQL/config.yml.
                Должен совпадать с ключом в секции databases (например, "economy-db").
                """)
        public String sourceId = "economy-db";

        @Comment("Максимум попыток при transient SQL-ошибках (deadlock, lock wait timeout)")
        public int retryMaxAttempts = 3;

        @Comment("Базовая задержка между попытками (мс, экспоненциально: base, base*3, base*9)")
        public long retryBaseDelayMs = 50;
    }

    @Configuration
    public static final class EconomySection {
        @Comment("""
            Выдавать стартовый баланс новым игрокам.
            false — новый аккаунт создаётся с нулём, startingBalance игнорируется.
            """)
        public boolean giveStartingBalance = true;

        @Comment("Стартовый баланс новых игроков (учитывается только если giveStartingBalance = true)")
        public BigDecimal startingBalance = BigDecimal.ZERO;

        @Comment("Максимальный баланс")
        public BigDecimal maxBalance = BigDecimal.valueOf(1_000_000_000);

        @Comment("Символ валюты")
        public String currencySymbol = "$";

        @Comment("Название валюты в единственном числе (Vault)")
        public String currencyNameSingular = "монета";

        @Comment("Название валюты во множественном числе (Vault)")
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

        @Comment("Порог суммы, выше которого требуется подтверждение (0 — отключено)")
        public BigDecimal confirmThreshold = BigDecimal.valueOf(1000);

        @Comment("Время на подтверждение (секунды)")
        public int confirmTimeout = 15;

        @Comment("Через сколько дней удалять недоставленные pending-платежи (защита от роста таблицы)")
        public int orphanedPendingMaxAgeDays = 7;
    }

    @Configuration
    public static final class NotifySection {
        @Comment("Звук при получении перевода (онлайн)")
        public SoundSection receiveSound = new SoundSection();

        @Comment("Звук при доставке офлайн-платежей при входе")
        public SoundSection offlineDeliveredSound = new SoundSection();

        @Configuration
        public static final class SoundSection {
            @Comment("Включить звук")
            public boolean enabled = true;

            @Comment("""
                    Ключ звука Minecraft (resource location).
                    Примеры: entity.experience_orb.pickup, block.note_block.pling, entity.item.pickup
                    """)
            public String key = "entity.experience_orb.pickup";

            @Comment("Громкость (0.0–1.0)")
            public float volume = 1.0f;

            @Comment("Высота тона (0.5–2.0)")
            public float pitch = 1.2f;
        }
    }

    @Configuration
    public static final class UpdatesSection {
        @Comment("Проверять обновления через GitHub Releases")
        public boolean check = true;
    }

    @Configuration
    public static final class RateLimitSection {
        @Comment("Кулдаун /pay между вызовами (мс). 0 — без ограничения")
        public long payCooldownMs = 1000;

        @Comment("Кулдаун /balance между вызовами (мс). 0 — без ограничения")
        public long balanceCooldownMs = 500;
    }
}