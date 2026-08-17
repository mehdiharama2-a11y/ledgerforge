package dev.ledgerforge.ledger;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class LedgerService {
  private final JdbcClient jdbc;
  public LedgerService(JdbcClient jdbc) { this.jdbc = jdbc; }

  public UUID accountId(UUID organizationId, String code) {
    return jdbc.sql("SELECT id FROM ledger_accounts WHERE organization_id=:org AND code=:code")
      .param("org", organizationId).param("code", code).query(UUID.class).single();
  }

  public UUID post(UUID organizationId, String referenceType, UUID referenceId, String description, List<PostingLine> lines) {
    LedgerInvariant.validate(lines);
    UUID transactionId = UUID.randomUUID();
    jdbc.sql("INSERT INTO ledger_transactions(id,organization_id,reference_type,reference_id,description) VALUES (:id,:org,:type,:ref,:description)")
      .params(Map.of("id", transactionId, "org", organizationId, "type", referenceType, "ref", referenceId, "description", description)).update();
    for (PostingLine line : lines) {
      jdbc.sql("INSERT INTO ledger_entries(organization_id,transaction_id,account_id,side,amount,currency) VALUES (:org,:tx,:account,:side,:amount,:currency)")
        .params(Map.of("org", organizationId, "tx", transactionId, "account", line.accountId(), "side", line.side().name(), "amount", line.amount(), "currency", line.currency())).update();
    }
    return transactionId;
  }
}
