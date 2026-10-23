BEGIN;

-- A user can hold several symbols, and one symbol can belong to several users.
ALTER TABLE holdings DROP CONSTRAINT IF EXISTS holdings_user_id_key;
ALTER TABLE holdings DROP CONSTRAINT IF EXISTS holdings_stock_symbol_key;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'holdings'::regclass
          AND conname = 'holdings_user_stock_unique'
    ) THEN
        ALTER TABLE holdings
            ADD CONSTRAINT holdings_user_stock_unique UNIQUE (user_id, stock_symbol);
    END IF;
END $$;

-- Watchlist identity is per user and symbol, not a globally unique display name.
ALTER TABLE watchlist DROP CONSTRAINT IF EXISTS watchlist_stock_name_key;
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'watchlist'::regclass
          AND conname = 'watchlist_user_symbol_unique'
    ) THEN
        ALTER TABLE watchlist
            ADD CONSTRAINT watchlist_user_symbol_unique UNIQUE (user_id, stock_symbol);
    END IF;
END $$;

-- Retries with a caller-supplied key return the first paper order instead of trading twice.
ALTER TABLE orders ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(100);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS request_hash VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS orders_user_idempotency_key_unique
    ON orders (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

COMMIT;
