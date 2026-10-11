# TerrEconomy

[![Version](https://img.shields.io/badge/version-1.0.0-blue.svg)](https://github.com/luci/TerrEconomy/releases)
[![Paper](https://img.shields.io/badge/Paper-1.21.11-orange.svg)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-21-red.svg)](https://adoptium.net/)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

Экономический плагин для Paper-серверов с поддержкой Vault, офлайн-переводов, налогов и локализации.

---

## ✨ Возможности

- 💰 **Полноценная экономика** — баланс, переводы, админ-команды
- 🔌 **Vault-совместимость** — работает со всеми плагинами через Vault
- 📬 **Офлайн-переводы** — платёж доставляется при входе получателя
- 💸 **Налог на перевод** — настраиваемый процент с каждой транзакции
- 🌍 **Мультиязычность** — у каждого игрока свой язык (клиентская локаль)
- 🎨 **Красивые уведомления** — Title, Action Bar, hover/click-события
- 💾 **H2 Database** — не требует внешней БД, всё локально
- ⚡ **Асинхронные операции** — БД не блокирует главный поток
- 🔄 **ConfigLib** — автоматическое обновление конфига
- 📊 **bStats** — анонимная статистика

---

## 📦 Установка

1. Скачай последний релиз из [Releases](https://github.com/luci/TerrEconomy/releases).
2. Положи `Economy-1.0.0.jar` в папку `plugins/`.
3. Установи зависимости:
    - [Vault](https://www.spigotmc.org/resources/vault.34315/) — **обязательно**
    - [CommandAPI](https://commandapi.jorel.dev/) — **обязательно**
    - [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) — опционально
4. Запусти сервер.
5. Настрой `plugins/TerrEconomy/config.yml` под себя.

---

## 🎮 Команды

| Команда | Описание | Права |
|---|---|---|
| `/balance [игрок]` | Показать свой или чужой баланс | — |
| `/bal`, `/money` | Алиасы `/balance` | — |
| `/pay <игрок> <сумма>` | Перевести деньги игроку | — |
| `/terrEco give <игрок> <сумма>` | Выдать деньги | `terracraft.eco.admin` |
| `/terrEco take <игрок> <сумма>` | Забрать деньги | `terracraft.eco.admin` |
| `/terrEco set <игрок> <сумма>` | Установить баланс | `terracraft.eco.admin` |
| `/terrEco reset <игрок>` | Сбросить баланс | `terracraft.eco.admin` |
| `/terrEco reload` | Перезагрузить конфиг и языки | `terracraft.eco.admin` |
| `/teco` | Алиас `/terrEco` | `terracraft.eco.admin` |

---

## 🔑 Права

| Право | Описание | По умолчанию |
|---|---|---|
| `terracraft.eco.admin` | Доступ к `/terrEco` | OP |

---

## ⚙️ Конфигурация

### `config.yml`

```yaml
lang:
  # Локаль по умолчанию для консоли и игроков с неподдерживаемым языком
  locale: ru_RU

database:
  # Имя файла H2 (без расширения)
  file: economy
  # Размер пула соединений
  pool-size: 5
  # Таймаут подключения (мс)
  connection-timeout: 10000

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

1. Скопируй `plugins/TerrEconomy/lang/en_US.yml`.
2. Переименуй в код своей локали, например `de_DE.yml`.
3. Переведи все сообщения.
4. Перезапусти сервер или выполни `/terrEco reload`.

---

## 🔌 Интеграция с Vault

Плагин **автоматически регистрируется в Vault** при запуске. Другие плагины видят экономику через стандартный API:

```java
RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
Economy economy = rsp.getProvider();
economy.depositPlayer(player, 100.0);
```

### PlaceholderAPI

Для использования плейсхолдеров установи expansion `Vault`:

```
/papi ecloud download Vault
/papi reload
```

Доступные плейсхолдеры:

| Плейсхолдер | Описание |
|---|---|
| `%vault_eco_balance%` | Баланс игрока (число) |
| `%vault_eco_balance_formatted%` | Баланс с символом валюты |
| `%vault_eco_balance_commas%` | Баланс с разделителями тысяч |

---

## 🛠️ Сборка из исходников

### Требования

- **JDK 21** или выше ([Adoptium](https://adoptium.net/))
- **Gradle 8.x** (или используй wrapper)

### Команды

```bash
git clone https://github.com/luci/TerrEconomy.git
cd TerrEconomy
./gradlew clean shadowJar
```

Готовый JAR появится в `build/libs/Economy-1.0.0.jar`.

### Структура проекта

```
src/main/java/ru/net/terracraft/eco/
├── TerrEconomyPlugin.java       # главный класс
├── command/                     # команды на CommandAPI
├── config/                      # PluginConfig + ConfigLoader
├── db/                          # DatabaseProvider + PlayerRepository
├── economy/                     # EconomyService, EconomyCache, Vault-провайдер
├── lang/                        # LangManager + LangKey (enum)
├── listener/                    # PlayerConnectionListener
└── util/                        # NotificationManager, UpdateNotifier

src/main/resources/
├── paper-plugin.yml
├── config.yml
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
- [ ] `/baltop` — топ богатейших
- [ ] `/money history` — история транзакций
- [ ] Миграция на MineSQL (общий пул для всех плагинов)

---

## 📄 Лицензия

MIT License — используй свободно, изменяй, распространяй.

---

## 💬 Поддержка

- **Issues**: [github.com/luci4ka/TerrCraftEconomy/issues](https://github.com/luci/TerrEconomy/issues)
- **Сайт**: [terracraft.net.ru](https://terracraft.net.ru)