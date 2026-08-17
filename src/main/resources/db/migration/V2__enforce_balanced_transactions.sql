CREATE OR REPLACE FUNCTION enforce_balanced_transaction() RETURNS trigger AS $$
DECLARE
  debit_total numeric(19,2);
  credit_total numeric(19,2);
  line_count integer;
BEGIN
  SELECT
    COALESCE(sum(amount) FILTER (WHERE side = 'DEBIT'), 0),
    COALESCE(sum(amount) FILTER (WHERE side = 'CREDIT'), 0),
    count(*)
  INTO debit_total, credit_total, line_count
  FROM ledger_entries
  WHERE transaction_id = NEW.transaction_id;

  IF line_count < 2 OR debit_total <> credit_total THEN
    RAISE EXCEPTION 'ledger transaction % is not balanced (debits %, credits %)', NEW.transaction_id, debit_total, credit_total;
  END IF;
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER balanced_ledger_transaction
AFTER INSERT ON ledger_entries
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION enforce_balanced_transaction();
