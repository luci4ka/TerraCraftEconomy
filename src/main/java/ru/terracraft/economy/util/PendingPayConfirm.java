package ru.terracraft.economy.util;

import java.math.BigDecimal;
import java.util.UUID;

public class PendingPayConfirm {

    private final UUID sender;
    private final String targetName;
    private final BigDecimal amount;
    private final long expireAt;

    public PendingPayConfirm(UUID sender, String targetName, BigDecimal amount, int timeoutSeconds) {
        this.sender = sender;
        this.targetName = targetName;
        this.amount = amount;
        this.expireAt = System.currentTimeMillis() + timeoutSeconds * 1000L;
    }

    public UUID getSender() { return sender; }
    public String getTargetName() { return targetName; }
    public BigDecimal getAmount() { return amount; }

    public boolean isExpired() {
        return System.currentTimeMillis() > expireAt;
    }
}