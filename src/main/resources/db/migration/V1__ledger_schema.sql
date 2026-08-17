CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE organizations (
  id uuid PRIMARY KEY,
  name text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ledger_accounts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES organizations(id),
  code text NOT NULL,
  name text NOT NULL,
  account_type text NOT NULL CHECK (account_type IN ('ASSET','LIABILITY','REVENUE','EXPENSE')),
  currency char(3) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (organization_id, code)
);

CREATE TABLE payments (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES organizations(id),
  idempotency_key text NOT NULL,
  amount numeric(19,2) NOT NULL CHECK (amount > 0),
  refunded_amount numeric(19,2) NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0 AND refunded_amount <= amount),
  currency char(3) NOT NULL,
  status text NOT NULL CHECK (status IN ('CAPTURED','PARTIALLY_REFUNDED','REFUNDED')),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (organization_id, idempotency_key)
);

CREATE TABLE refunds (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES organizations(id),
  payment_id uuid NOT NULL REFERENCES payments(id),
  idempotency_key text NOT NULL,
  amount numeric(19,2) NOT NULL CHECK (amount > 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (organization_id, idempotency_key)
);

CREATE TABLE ledger_transactions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES organizations(id),
  reference_type text NOT NULL CHECK (reference_type IN ('PAYMENT','REFUND')),
  reference_id uuid NOT NULL,
  description text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (organization_id, reference_type, reference_id)
);

CREATE TABLE ledger_entries (
  id bigserial PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES organizations(id),
  transaction_id uuid NOT NULL REFERENCES ledger_transactions(id),
  account_id uuid NOT NULL REFERENCES ledger_accounts(id),
  side text NOT NULL CHECK (side IN ('DEBIT','CREDIT')),
  amount numeric(19,2) NOT NULL CHECK (amount > 0),
  currency char(3) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_entries_transaction ON ledger_entries(transaction_id);
CREATE INDEX idx_payments_org_created ON payments(organization_id, created_at DESC);

CREATE OR REPLACE FUNCTION reject_ledger_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'ledger history is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER immutable_ledger_transactions BEFORE UPDATE OR DELETE ON ledger_transactions
FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();
CREATE TRIGGER immutable_ledger_entries BEFORE UPDATE OR DELETE ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION reject_ledger_mutation();

INSERT INTO organizations (id, name) VALUES ('00000000-0000-4000-8000-000000000001', 'LedgerForge Demo');
INSERT INTO ledger_accounts (organization_id, code, name, account_type, currency) VALUES
('00000000-0000-4000-8000-000000000001', 'CASH', 'Cash clearing', 'ASSET', 'USD'),
('00000000-0000-4000-8000-000000000001', 'MERCHANT_PAYABLE', 'Merchant payable', 'LIABILITY', 'USD');
