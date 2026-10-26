package ru.terracraft.economy.lang;

public enum LangKey {

    GENERAL_ONLY_PLAYERS("general.only-players"),
    GENERAL_NO_PERMISSION("general.no-permission"),
    GENERAL_PLAYER_NOT_FOUND("general.player-not-found"),
    GENERAL_PLAYER_NEVER_JOINED("general.player-never-joined"),
    GENERAL_INVALID_AMOUNT("general.invalid-amount"),
    GENERAL_NEGATIVE_AMOUNT("general.negative-amount"),
    GENERAL_SELF_ACTION("general.self-action"),
    GENERAL_RELOADED("general.reloaded"),
    GENERAL_PAYCONFIRM("general.payconfirm"),
    GENERAL_PAYCONFIRM_REPLACED("general.payconfirm-replaced"),
    GENERAL_PAYCONFIRM_TIMEOUT("general.payconfirm-timeout"),

    BALANCE_SELF("balance.self"),
    BALANCE_OTHER("balance.other"),
    BALANCE_TOP_HEADER("balance.top-header"),
    BALANCE_TOP_LINE("balance.top-line"),
    BALANCE_TOP_EMPTY("balance.top-empty"),

    PAY_SUCCESS_SENDER("pay.success-sender"),
    PAY_SUCCESS_TARGET("pay.success-target"),
    PAY_TAX_NOTICE("pay.tax-notice"),
    PAY_OFFLINE_QUEUED("pay.offline-queued"),
    PAY_OFFLINE_DELIVERED("pay.offline-delivered"),
    NOTIFY_BALANCE_OTHER_LABEL("notify.balance.other-label"),
    PAY_OFFLINE_SUMMARY("pay.offline-summary"),
    PAY_INSUFFICIENT_FUNDS("pay.insufficient-funds"),
    PAY_TARGET_MAX_BALANCE("pay.target-max-balance"),

    ECO_GIVE("eco.give"),
    ECO_TAKE("eco.take"),
    ECO_TAKE_INSUFFICIENT("eco.take-insufficient"),
    ECO_SET("eco.set"),
    ECO_RESET("eco.reset"),
    ECO_CLEANUP("eco.cleanup"),

    NOTIFY_PAY_SENT_TITLE("notify.pay.sent-title"),
    NOTIFY_PAY_LABEL_TARGET("notify.pay.label-target"),
    NOTIFY_PAY_RECEIVED_TITLE("notify.pay.received-title"),
    NOTIFY_PAY_RECEIVED_SUBTITLE("notify.pay.received-subtitle"),
    NOTIFY_PAY_LABEL_SENDER("notify.pay.label-sender"),
    NOTIFY_PAY_ACTION_SENT("notify.pay.action-sent"),
    NOTIFY_PAY_ACTION_RECEIVED("notify.pay.action-received"),
    NOTIFY_PAY_ACTION_QUEUED("notify.pay.action-queued"),
    NOTIFY_PAY_QUEUED_TITLE("notify.pay.queued-title"),
    NOTIFY_PAY_QUEUED_LINE_TARGET("notify.pay.queued-line-target"),
    NOTIFY_PAY_QUEUED_LINE_AMOUNT("notify.pay.queued-line-amount"),
    NOTIFY_PAY_QUEUED_LINE_TAX("notify.pay.queued-line-tax"),
    NOTIFY_PAY_QUEUED_LINE_DELIVER("notify.pay.queued-line-deliver"),
    NOTIFY_PAY_DELIVERED_TITLE("notify.pay.delivered-title"),
    NOTIFY_PAY_DELIVERED_SUBTITLE("notify.pay.delivered-subtitle"),
    NOTIFY_PAY_DELIVERED_SUMMARY("notify.pay.delivered-summary"),
    NOTIFY_RECEIPT_HOVER_NAME("notify.receipt.hover-name"),
    NOTIFY_RECEIPT_HOVER_TAX("notify.receipt.hover-tax"),
    NOTIFY_RECEIPT_LINE_AMOUNT("notify.receipt.line-amount"),
    NOTIFY_RECEIPT_LINE_TAX("notify.receipt.line-tax"),
    NOTIFY_RECEIPT_LINE_TOTAL("notify.receipt.line-total"),
    NOTIFY_BALANCE_SELF_TITLE("notify.balance.self-title"),
    NOTIFY_BALANCE_SELF_HOVER("notify.balance.self-hover"),
    NOTIFY_BALANCE_OTHER_TITLE("notify.balance.other-title"),
    NOTIFY_BALANCE_OTHER_HOVER("notify.balance.other-hover"),
    ABBREV_K("abbrev.k"),
    ABBREV_M("abbrev.m"),
    ABBREV_B("abbrev.b"),
    NOTIFY_BALANCE_ACTION("notify.balance.action");

    private final String key;

    LangKey(String key) { this.key = key; }

    public String key() { return key; }
}