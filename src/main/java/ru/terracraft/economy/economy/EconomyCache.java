package ru.terracraft.economy.economy;

import ru.terracraft.economy.config.PluginConfig;
import ru.terracraft.economy.db.PlayerRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class EconomyCache {

    private final PluginConfig config;
    private final Map<UUID, BigDecimal> balances = new ConcurrentHashMap<>();

    public EconomyCache(PluginConfig config) {
        this.config = config;
    }

    public boolean hasAccount(UUID uuid) {
        return balances.containsKey(uuid);
    }

    public BigDecimal getBalance(UUID uuid) {
        return balances.getOrDefault(uuid, BigDecimal.ZERO);
    }

    public void createAccount(UUID uuid) {
        var starting = config.economy.giveStartingBalance
                ? config.economy.startingBalance
                : BigDecimal.ZERO;
        createAccount(uuid, starting);
    }

    /**
     * Создаёт аккаунт с явным стартовым балансом. Не перезаписывает существующий
     * (putIfAbsent). Валидация maxBalance не нужна — стартовый баланс задаётся
     * админом и предполагается в пределах лимита.
     */
    public void createAccount(UUID uuid, BigDecimal starting) {
        balances.putIfAbsent(uuid, scale(starting));
    }

    /**
     * Загрузка баланса из БД. Без валидации maxBalance — это «источник истины»,
     * его нельзя обрезать при чтении (иначе при следующем сохранении потеряем данные).
     */
    public void load(UUID uuid, BigDecimal balance) {
        balances.put(uuid, scale(balance));
    }

    /**
     * Атомарное списание. Возвращает новый баланс или empty, если средств
     * недостаточно. Проверка и списание — под одной блокировкой, поэтому
     * увести баланс в минус невозможно.
     */
    public synchronized Optional<BigDecimal> withdrawIfEnough(UUID uuid, BigDecimal amount) {
        var current = balances.getOrDefault(uuid, BigDecimal.ZERO);
        if (current.compareTo(amount) < 0) {
            return Optional.empty();
        }
        var newBalance = scale(current.subtract(amount));
        balances.put(uuid, newBalance);
        return Optional.of(newBalance);
    }

    /**
     * Атомарное начисление с учётом maxBalance. Возвращает результат:
     * - newBalance — итоговый баланс получателя;
     * - credited — сколько реально зачислено (может быть меньше amount,
     *   если упёрлись в maxBalance);
     * - overflow — сколько не поместилось (amount - credited).
     * Вызывающий сам решает, что делать с overflow (вернуть отправителю,
     * залогировать, отклонить операцию).
     */
    public synchronized DepositResult depositCapped(UUID uuid, BigDecimal amount) {
        var current = balances.getOrDefault(uuid, BigDecimal.ZERO);
        var raw = scale(current.add(amount));
        var max = scale(config.economy.maxBalance);

        BigDecimal newBalance;
        BigDecimal credited;
        if (raw.compareTo(max) > 0) {
            newBalance = max;
            credited = scale(max.subtract(current));
            if (credited.signum() < 0) {
                // Уже на максимуме — зачислять нечего.
                credited = BigDecimal.ZERO;
            }
        } else {
            newBalance = raw;
            credited = scale(amount);
        }

        balances.put(uuid, newBalance);
        var overflow = scale(amount.subtract(credited));
        return new DepositResult(newBalance, credited, overflow);
    }

    /**
     * Прямая установка баланса (админ). Валидирует maxBalance.
     * Возвращает фактически установленный баланс.
     */
    public synchronized BigDecimal set(UUID uuid, BigDecimal amount) {
        var capped = scale(amount);
        var max = scale(config.economy.maxBalance);
        if (capped.compareTo(max) > 0) {
            capped = max;
        }
        if (capped.signum() < 0) {
            capped = BigDecimal.ZERO;
        }
        balances.put(uuid, capped);
        return capped;
    }

    /**
     * Снимок всех балансов для flush при выгрузке.
     * Возвращает копию, чтобы не держать блокировку во время записи в БД.
     */
    public Map<UUID, BigDecimal> snapshot() {
        return Map.copyOf(balances);
    }

    /**
     * @deprecated используйте snapshot() + явную запись, чтобы контролировать
     * порядок и не гонять запись внутри forEach. Оставлено для совместимости.
     */
    @Deprecated
    public void flushAll(PlayerRepository repo) {
        snapshot().forEach(repo::updateBalance);
    }

    private BigDecimal scale(BigDecimal value) {
        return value.setScale(config.economy.fractionalDigits, RoundingMode.HALF_UP);
    }

    public record DepositResult(BigDecimal newBalance, BigDecimal credited, BigDecimal overflow) {}
}