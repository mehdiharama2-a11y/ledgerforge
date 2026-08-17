ALTER TABLE organizations ADD COLUMN slug text;
ALTER TABLE organizations ADD COLUMN webhook_secret text;
UPDATE organizations SET slug='demo', webhook_secret='whsec_demo_ledgerforge' WHERE id='00000000-0000-4000-8000-000000000001';
ALTER TABLE organizations ALTER COLUMN slug SET NOT NULL;
ALTER TABLE organizations ALTER COLUMN webhook_secret SET NOT NULL;
ALTER TABLE organizations ADD CONSTRAINT organizations_slug_key UNIQUE (slug);

CREATE TABLE users (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES organizations(id),
  email text NOT NULL,
  password_hash text NOT NULL,
  role text NOT NULL CHECK (role IN ('ADMIN','OPERATOR','AUDITOR')),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (organization_id, email)
);

CREATE TABLE webhook_receipts (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES organizations(id),
  provider_event_id text NOT NULL,
  event_type text NOT NULL,
  payment_reference text NOT NULL,
  occurred_at timestamptz NOT NULL,
  status text NOT NULL CHECK (status IN ('PENDING','PROCESSED','REJECTED')),
  payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  processed_at timestamptz,
  UNIQUE (organization_id, provider_event_id)
);

CREATE TABLE outbox_events (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  organization_id uuid NOT NULL REFERENCES organizations(id),
  aggregate_type text NOT NULL,
  aggregate_id uuid NOT NULL,
  event_type text NOT NULL,
  payload jsonb NOT NULL,
  status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','PUBLISHED','FAILED')),
  attempts integer NOT NULL DEFAULT 0,
  available_at timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz,
  last_error text,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_dispatch ON outbox_events(status, available_at) WHERE status IN ('PENDING','FAILED');

CREATE TABLE published_events (
  id bigserial PRIMARY KEY,
  outbox_id uuid NOT NULL UNIQUE REFERENCES outbox_events(id),
  organization_id uuid NOT NULL REFERENCES organizations(id),
  event_type text NOT NULL,
  payload jsonb NOT NULL,
  published_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE reconciliation_results (
  payment_id uuid PRIMARY KEY REFERENCES payments(id),
  organization_id uuid NOT NULL REFERENCES organizations(id),
  status text NOT NULL CHECK (status IN ('MATCHED','MISSING','MISMATCHED')),
  expected_amount numeric(19,2) NOT NULL,
  ledger_amount numeric(19,2),
  details text NOT NULL,
  checked_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO users (organization_id,email,password_hash,role) VALUES
('00000000-0000-4000-8000-000000000001','admin@ledgerforge.dev',crypt('LedgerForge123!',gen_salt('bf')),'ADMIN'),
('00000000-0000-4000-8000-000000000001','operator@ledgerforge.dev',crypt('LedgerForge123!',gen_salt('bf')),'OPERATOR'),
('00000000-0000-4000-8000-000000000001','auditor@ledgerforge.dev',crypt('LedgerForge123!',gen_salt('bf')),'AUDITOR');

INSERT INTO organizations (id,name,slug,webhook_secret) VALUES
('00000000-0000-4000-8000-000000000011','LedgerForge Acme','acme','whsec_acme_ledgerforge');
INSERT INTO ledger_accounts (organization_id,code,name,account_type,currency) VALUES
('00000000-0000-4000-8000-000000000011','CASH','Cash clearing','ASSET','USD'),
('00000000-0000-4000-8000-000000000011','MERCHANT_PAYABLE','Merchant payable','LIABILITY','USD');
INSERT INTO users (organization_id,email,password_hash,role) VALUES
('00000000-0000-4000-8000-000000000011','admin@acme.test',crypt('AcmeLedger123!',gen_salt('bf')),'ADMIN');
