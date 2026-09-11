package dev.aperture.account;

import java.util.Objects;

/**
 * One of the account holder's Webull accounts.
 *
 * <p>{@code accountType} and {@code accountClass} are the vendor's own classifications - CASH
 * versus MARGIN, individual versus IRA - carried through verbatim rather than mapped onto an
 * internal taxonomy, because a margin account and a cash account genuinely behave differently and
 * flattening that distinction would hide it.
 *
 * <p>The environment is part of the identity, not a property looked up later. The same person has
 * different accounts in production and sandbox, and an account object that does not know which it
 * belongs to is one careless assignment away from routing a paper order at the live book.
 */
public record BrokerAccount(
        String accountId,
        String accountNumber,
        String accountType,
        String accountClass,
        String label,
        TradingEnvironment environment) {

    public BrokerAccount {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(environment, "environment");
        accountNumber = accountNumber == null ? "" : accountNumber;
        accountType = accountType == null ? "" : accountType;
        accountClass = accountClass == null ? "" : accountClass;
        label = label == null ? "" : label;
    }

    /** A composite key, because account ids are only unique within an environment. */
    public String key() {
        return environment.name() + ":" + accountId;
    }

    /** What to show in the account picker. */
    public String displayName() {
        StringBuilder name = new StringBuilder();
        name.append(label.isBlank() ? maskedNumber() : label);
        if (!accountType.isBlank()) {
            name.append(" (").append(accountType).append(')');
        }
        return name.toString();
    }

    /**
     * The account number with all but the last four digits masked.
     *
     * <p>The full number is never rendered. It identifies a real brokerage account and the UI has
     * no use for it beyond telling two accounts apart, which four digits do.
     */
    public String maskedNumber() {
        if (accountNumber.length() <= 4) {
            return accountNumber.isBlank() ? accountId : accountNumber;
        }
        return "****" + accountNumber.substring(accountNumber.length() - 4);
    }

    public boolean isMargin() {
        return accountType.toUpperCase().contains("MARGIN");
    }

    public boolean isReal() {
        return environment.isReal();
    }
}
