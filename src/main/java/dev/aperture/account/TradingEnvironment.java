package dev.aperture.account;

/**
 * Which Webull environment a client is talking to.
 *
 * <p>The two differ only by endpoint - same credential shape, same request types - which is
 * exactly what makes them dangerous to conflate. Nothing in a response says "this was real
 * money", so the environment is carried explicitly on every account, quote source and order path
 * rather than being ambient state someone can forget to check.
 */
public enum TradingEnvironment {

    /** Webull's paper-trading environment. Orders here are simulated. */
    SANDBOX("Sandbox", "api.sandbox.webull.com", false),

    /** The live brokerage. Orders here move real money. */
    PRODUCTION("Production", "api.webull.com", true);

    private final String label;
    private final String endpoint;
    private final boolean real;

    TradingEnvironment(String label, String endpoint, boolean real) {
        this.label = label;
        this.endpoint = endpoint;
        this.real = real;
    }

    public String label() {
        return label;
    }

    public String endpoint() {
        return endpoint;
    }

    /** Whether orders placed here execute against real money. */
    public boolean isReal() {
        return real;
    }

    public static TradingEnvironment parse(String value) {
        if (value == null || value.isBlank()) {
            return SANDBOX;
        }
        String normalised = value.trim().toUpperCase();
        return switch (normalised) {
            case "PRODUCTION", "PROD", "LIVE" -> PRODUCTION;
            case "SANDBOX", "PAPER", "SIM", "SIMULATED" -> SANDBOX;
            default -> throw new IllegalArgumentException("Unknown trading environment: " + value);
        };
    }

    /**
     * Defaults to {@link #SANDBOX} for anything unrecognised.
     *
     * <p>Used on inbound request parameters, where the safe failure is to fall back to paper
     * rather than to reject - or worse, to guess production.
     */
    public static TradingEnvironment parseOrSandbox(String value) {
        try {
            return parse(value);
        } catch (IllegalArgumentException e) {
            return SANDBOX;
        }
    }
}
