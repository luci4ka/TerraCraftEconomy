# TerrEconomy

[![Version](https://img.shields.io/badge/version-1.0.1-blue.svg)](https://github.com/luci4ka/TerrCraftEconomy/releases)
[![Paper](https://img.shields.io/badge/Paper-26.3-orange.svg)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-25-red.svg)](https://adoptium.net/)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

Экономический плагин для Paper-серверов с поддержкой Vault, офлайн-переводов, налогов, подтверждения крупных переводов, логирования транзакций и локализации.

---

## ✨ Возможности

- 💰 **Полноценная экономика** — баланс, переводы, админ-команды
- 🔌 **Vault-совместимость** — работает со всеми плагинами через Vault
- 📬 **Офлайн-переводы** — платёж доставляется при входе получателя
- 💸 **Налог на перевод** — настраиваемый процент с каждой транзакции
- ✅ **Подтверждение крупных переводов** — защита от опечаток через `/payconfirm`
- 📜 **Логирование транзакций** — все операции пишутся в таблицу `transactions`
- 🧹 **Очистка мёртвых аккаунтов** — `/eco cleanup <days>`
- 🌍 **Мультиязычность** — у каждого игрока свой язык (клиентская локаль)
- 🎨 **Красивые уведомления** — Title, Action Bar, hover/click-события
- 💾 **MineSQL + MariaDB** — общий пул соединений для всех плагинов
- ⚡ **Асинхронные операции** — БД не блокирует главный поток
- 🔄 **ConfigLib** — автоматическое обновление конфига
- 🧩 **PlaceholderAPI** — баланс, форматирование, сокращения (`1.5к`, `$1.5к`)
- 📊 **bStats** — анонимная статистика

---

## 📦 Установка

1. Скачай последний релиз из [Releases](https://github.com/luci4ka/TerrCraftEconomy/releases).
2. Положи `TerrCraftEconomy-1.0.1.jar` в папку `plugins/`.
3. Установи зависимости:
   - [Vault](https://www.spigotmc.org/resources/vault.34315/) — **обязательно**
   - [CommandAPI](https://commandapi.jorel.dev/) — **обязательно**
   - [MineSQL](https://github.com/CarmJos/MineSQL) — **обязательно** (для подключения к MariaDB)
   - [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) — опционально
4. Запусти сервер.
5. Настрой источник данных в `plugins/MineSQL/config.yml` (см. ниже).
6. Настрой `plugins/TerrCraftEconomy/config.yml` под себя.

### Настройка MineSQL

В `plugins/MineSQL/config.yml` создай источник данных с ID, который указан в `config.yml` плагина (`database.source-id`, по умолчанию `economy-db`):

```yaml
databases:
  "economy-db":
    driver-type: mariadb
    host: 127.0.0.1
    port: 3306
    database: terracraft_eco
    username: your_user
    password: your_password
```

> Для MariaDB рекомендуется указывать `driver-type: mariadb`, а не `mysql`.

---

## 🎮 Команды

| Команда | Описание | Права |
|---|---|---|
| `/balance [игрок]` | Показать свой или чужой баланс | — |
| `/bal`, `/money` | Алиасы `/balance` | — |
| `/pay <игрок> <сумма>` | Перевести деньги игроку | — |
| `/payconfirm` | Подтвердить крупный перевод | — |
| `/eco give <игрок> <сумма>` | Выдать деньги | `terracraft.economy.admin` |
| `/eco take <игрок> <сумма>` | Забрать деньги | `terracraft.economy.admin` |
| `/eco set <игрок> <сумма>` | Установить баланс | `terracraft.economy.admin` |
| `/eco reset <игрок>` | Сбросить баланс | `terracraft.economy.admin` |
| `/eco cleanup <days>` | Удалить неактивные аккаунты с нулевым балансом | `terracraft.economy.admin` |
| `/eco reload` | Перезагрузить конфиг и языки | `terracraft.economy.admin` |

---

## 🔑 Права

| Право | Описание | По умолчанию |
|---|---|---|
| `terracraft.economy.admin` | Доступ к `/eco ...` | OP |

---

## ⚙️ Конфигурация

### `config.yml`

```yaml
lang:
  # Локаль по умолчанию для консоли и игроков с неподдерживаемым языком
  locale: ru_RU

database:
  # ID источника данных в plugins/MineSQL/config.yml
  source-id: economy-db

economy:
  # Стартовый баланс новых игроков
  starting-balance: 0.0
  # Максимальный баланс
  max-balance: 1000000000.0
  # Символ валюты
  currency-symbol: "$"
  # Название валюты в единственном числе
  currency-name-singular: "монета"
  # Название валюты во множественном числе
  currency-name-plural: "монет"
  # Знаков после запятой
  fractional-digits: 2
  # Интервал автосохранения (секунды)
  auto-save-interval: 60

pay:
  # Процент налога (0 — отключено)
  tax-percent: 0.0
  # Минимальная сумма, с которой берётся налог
  min-tax-amount: 1.0
  # Максимальный налог с одной операции (0 — без ограничения)
  max-tax-amount: 1000.0
  # Разрешить переводы офлайн-игрокам
  allow-offline: true
  # Порог суммы, выше которого требуется подтверждение (0 — отключено)
  confirm-threshold: 1000.0
  # Время на подтверждение (секунды)
  confirm-timeout: 15

updates:
  # Проверять обновления при старте сервера
  check: true
```

---

## 🌍 Локализация

Плагин поддерживает **автоматический выбор языка** по клиентской локали игрока.

### Как это работает

1. Игрок заходит с клиентом на русском → видит `ru_RU.yml`.
2. Игрок заходит с клиентом на английском → видит `en_US.yml`.
3. Если локали нет в папке `lang/` → используется `locale` из конфига.

### Добавление своего языка

1. Скопируй `plugins/TerrCraftEconomy/lang/en_US.yml`.
2. Переименуй в код своей локали, например `de_DE.yml`.
3. Переведи все сообщения (включая `abbrev.k/m/b` — суффиксы сокращений).
4. Перезапусти сервер или выполни `/eco reload`.

---

## 🔌 Интеграция с Vault

Плагин **автоматически регистрируется в Vault** при запуске. Другие плагины видят экономику через стандартный API:

```java
RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
Economy economy = rsp.getProvider();
economy.depositPlayer(player, 100.0);
```

### PlaceholderAPI

Плагин регистрирует собственное расширение `terr_economy`. Доступные плейсхолдеры:

| Плейсхолдер | Описание | Пример |
|---|---|---|
| `%terr_economy_balance%` | Баланс без округления | `1234.5678` |
| `%terr_economy_balance_format%` | Баланс с округлением | `1234.57` |
| `%terr_economy_balance_format_symvol%` | Баланс с символом валюты | `$1234.57` |
| `%terr_economy_balance_short%` | Сокращённый баланс | `1.2к` |
| `%terr_economy_balance_short_symvol%` | Сокращённый баланс с символом | `$1.2к` |

Суффиксы сокращений (`к`, `М`, `млрд` / `k`, `M`, `B`) настраиваются в языковых файлах через ключи `abbrev.k`, `abbrev.m`, `abbrev.b`.

---

## 🛠️ Сборка из исходников

### Требования

- **JDK 25** ([Adoptium](https://adoptium.net/))
- **Gradle 8.x** (или используй wrapper)

### Команды

```bash
git clone https://github.com/luci4ka/TerrCraftEconomy.git
cd TerrCraftEconomy
./gradlew clean shadowJar
```

Готовый JAR появится в `build/libs/TerrCraftEconomy-1.0.1.jar`.

### Структура проекта

```
src/main/java/ru/terracraft/economy/
├── TerrEconomyPlugin.java       # главный класс
├── command/                     # команды на CommandAPI
├── config/                      # PluginConfig + ConfigLoader
├── db/                          # DatabaseProvider + PlayerRepository
├── economy/                     # EconomyService, EconomyCache, Vault-провайдер
├── hook/                        # TerrEconomyExpansion (PlaceholderAPI)
├── lang/                        # LangManager + LangKey (enum)
├── listener/                    # PlayerConnectionListener
└── util/                        # NotificationManager, UpdateNotifier, PendingPayManager

src/main/resources/
├── paper-plugin.yml
└── lang/
    ├── ru_RU.yml
    └── en_US.yml
```

---

## 🗺️ Roadmap

- [x] Базовая экономика + Vault
- [x] Переводы между игроками
- [x] Налог на перевод
- [x] Офлайн-переводы
- [x] Локализация по клиенту
- [x] Красивые уведомления
- [x] ConfigLib + автообновление конфига
- [x] Подтверждение крупных переводов
- [x] Логирование транзакций в БД
- [x] Очистка мёртвых аккаунтов
- [x] PlaceholderAPI-расширение (включая сокращения)
- [x] Миграция на MineSQL + MariaDB
- [ ] `/eco history <player>` — просмотр истории транзакций в чате
- [ ] Отдельный тип транзакции `OFFLINE_PAY` для офлайн-переводов

---

## 📄 Лицензия

MIT License — используй свободно, изменяй, распространяй.

---

## 💬 Поддержка

- **Issues**: [github.com/luci4ka/TerrCraftEconomy/issues](https://github.com/luci4ka/TerrCraftEconomy/issues)

