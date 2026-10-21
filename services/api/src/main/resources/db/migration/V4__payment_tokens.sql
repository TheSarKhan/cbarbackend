-- One-time, scoped payment tokens: only the SHA-256 of a token is stored, so a database leak yields nothing spendable.
CREATE TABLE IF NOT EXISTS payment_tokens (
  token_hash VARCHAR(64) PRIMARY KEY,
  user_id VARCHAR(64) NOT NULL,
  scope VARCHAR(32) NOT NULL,
  binding TEXT NOT NULL,
  expires_at BIGINT NOT NULL,
  used_at BIGINT
);
CREATE INDEX IF NOT EXISTS payment_tokens_expiry ON payment_tokens(expires_at);
