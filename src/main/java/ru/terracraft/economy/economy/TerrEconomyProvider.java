package ru.terracraft.economy.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import ru.terracraft.economy.config.PluginConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@SuppressWarnings("deprecation")
public class TerrEconomyProvider implements Economy {

    private final Plugin plugin;
    private final EconomyService service;
    private final PluginConfig config;

    public TerrEconomyProvider(Plugin plugin, EconomyService service, PluginConfig config) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
    }

    @Override public boolean isEnabled() { return plugin.isEnabled(); }
    @Override public String getName() { return "TerrEconomy"; }

    @Override
    public boolean hasBankSupport() { return false; }

    @Override
    public int fractionalDigits() {
        return config.economy.fractionalDigits;
    }

    @Override
    public String format(double amount) {
        return config.economy.currencySymbol + BigDecimal.valueOf(amount)
                .setScale(config.economy.fractionalDigits, RoundingMode.HALF_UP)
                .toPlainString();
    }

    @Override
    public String currencyNamePlural() {
        return config.economy.currencyNamePlural;
    }

    @Override
    public String currencyNameSingular() {
        return config.economy.currencyNameSingular;
    }

    // ============ UUID-методы ============
    @Override
    public boolean hasAccount(UUID uuid) {
        return uuid != null && service.hasAccount(uuid);
    }

    @Override
    public boolean hasAccount(UUID uuid, String worldName) {
        return hasAccount(uuid);
    }

    @Override
    public boolean createPlayerAccount(UUID uuid) {
        if (uuid == null || service.hasAccount(uuid)) return false;
        var p = Bukkit.getOfflinePlayer(uuid);
        service.createAccount(uuid, p.getName());
        return true;
    }

    @Override
    public boolean createPlayerAccount(UUID uuid, String worldName) {
        return createPlayerAccount(uuid);
    }

    @Override
    public double getBalance(UUID uuid) {
        return uuid == null ? 0.0 : service.getBalance(uuid).doubleValue();
    }

    @Override
    public double getBalance(UUID uuid, String worldName) {
        return getBalance(uuid);
    }

    @Override
    public boolean has(UUID uuid, double amount) {
        return uuid != null && service.getBalance(uuid).doubleValue() >= amount;
    }

    @Override
    public boolean has(UUID uuid, String worldName, double amount) {
        return has(uuid, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(UUID uuid, double amount) {
        if (uuid == null) return fail("Игрок не найден");
        if (amount < 0) return fail("Сумма не может быть отрицательной");

        var newBalance = service.withdraw(uuid, BigDecimal.valueOf(amount));
        if (newBalance == null) {
            return new EconomyResponse(0, service.getBalance(uuid).doubleValue(),
                    EconomyResponse.ResponseType.FAILURE, "Недостаточно средств");
        }
        return new EconomyResponse(amount, newBalance.doubleValue(),
                EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse withdrawPlayer(UUID uuid, String worldName, double amount) {
        return withdrawPlayer(uuid, amount);
    }

    @Override
    public EconomyResponse depositPlayer(UUID uuid, double amount) {
        if (uuid == null) return fail("Игрок не найден");
        if (amount < 0) return fail("Сумма не может быть отрицательной");

        var newBalance = service.deposit(uuid, BigDecimal.valueOf(amount));
        return new EconomyResponse(amount, newBalance.doubleValue(),
                EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse depositPlayer(UUID uuid, String worldName, double amount) {
        return depositPlayer(uuid, amount);
    }

    // ============ OfflinePlayer-методы ============
    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return player != null && service.hasAccount(player.getUniqueId());
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        if (player == null || hasAccount(player)) return false;
        service.createAccount(player.getUniqueId(), player.getName());
        return true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return player == null ? 0.0 : service.getBalance(player.getUniqueId()).doubleValue();
    }

    @Override
    public double getBalance(OfflinePlayer player, String worldName) {
        return getBalance(player);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return player != null && service.getBalance(player.getUniqueId()).doubleValue() >= amount;
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        if (player == null) return fail("Игрок не найден");
        return withdrawPlayer(player.getUniqueId(), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (player == null) return fail("Игрок не найден");
        return depositPlayer(player.getUniqueId(), amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    // ============ String-методы ============
    @Override
    public boolean hasAccount(String playerName) {
        var p = Bukkit.getOfflinePlayerIfCached(playerName);
        return p != null && hasAccount(p);
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        var p = Bukkit.getOfflinePlayerIfCached(playerName);
        return p != null && createPlayerAccount(p);
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public double getBalance(String playerName) {
        var p = Bukkit.getOfflinePlayerIfCached(playerName);
        return p == null ? 0.0 : getBalance(p);
    }

    @Override
    public double getBalance(String playerName, String worldName) {
        return getBalance(playerName);
    }

    @Override
    public boolean has(String playerName, double amount) {
        return getBalance(playerName) >= amount;
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        var p = Bukkit.getOfflinePlayerIfCached(playerName);
        if (p == null) return fail("Игрок не найден");
        return withdrawPlayer(p, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        var p = Bukkit.getOfflinePlayerIfCached(playerName);
        if (p == null) return fail("Игрок не найден");
        return depositPlayer(p, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    // ============ Банки (не поддерживаются) ============
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return notImpl(); }
    @Override public EconomyResponse createBank(String name, UUID uuid) { return notImpl(); }
    @Override public EconomyResponse createBank(String name, String playerName) { return notImpl(); }
    @Override public EconomyResponse deleteBank(String name) { return notImpl(); }
    @Override public EconomyResponse bankBalance(String name) { return notImpl(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return notImpl(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return notImpl(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return notImpl(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return notImpl(); }
    @Override public EconomyResponse isBankOwner(String name, UUID uuid) { return notImpl(); }
    @Override public EconomyResponse isBankOwner(String name, String playerName) { return notImpl(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return notImpl(); }
    @Override public EconomyResponse isBankMember(String name, UUID uuid) { return notImpl(); }
    @Override public EconomyResponse isBankMember(String name, String playerName) { return notImpl(); }
    @Override public List<String> getBanks() { return List.of(); }

    // ============ Вспомогательные ============
    private EconomyResponse fail(String msg) {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.FAILURE, msg);
    }

    private EconomyResponse notImpl() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Банки не поддерживаются");
    }
}