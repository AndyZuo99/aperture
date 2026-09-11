-- Aperture baseline schema.
--
-- Only corporate actions are persisted. Quotes and daily bars are deliberately NOT stored: both
-- are re-fetchable from the vendor in a second, and a stale local copy of market data is worse
-- than no copy because it looks authoritative. Declared corporate actions are the one thing here
-- that cannot be re-derived from anywhere - if they were lost on restart, an adjusted chart would
-- silently revert to an unadjusted one.

CREATE TABLE corporate_actions (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,

    instrument_id       VARCHAR(64)   NOT NULL,
    action_type         VARCHAR(32)   NOT NULL,
    ex_date             DATE          NOT NULL,

    -- Split and stock-dividend ratios. Null for cash dividends.
    new_shares          DECIMAL(20, 8),
    old_shares          DECIMAL(20, 8),
    shares_per_share    DECIMAL(20, 8),

    -- Cash dividend fields. Null for splits.
    amount_per_share    DECIMAL(20, 6),
    pay_date            DATE,

    -- Symbol change fields.
    old_symbol          VARCHAR(32),
    new_symbol          VARCHAR(32),

    -- Where this came from: VENDOR, REFERENCE or DECLARED. Displayed in the UI, because an
    -- adjustment driven by a hand-entered action is a weaker claim than one from a data feed.
    source              VARCHAR(16)   NOT NULL,
    recorded_at         TIMESTAMP     NOT NULL,

    -- De-duplication key: the same action learned twice must not be applied twice, which would
    -- square the adjustment factor.
    dedupe_key          VARCHAR(255)  NOT NULL,

    CONSTRAINT uq_corporate_actions_dedupe UNIQUE (dedupe_key)
);

CREATE INDEX idx_corporate_actions_instrument ON corporate_actions (instrument_id, ex_date);
CREATE INDEX idx_corporate_actions_ex_date ON corporate_actions (ex_date);
