package dev.ledgerforge.reconciliation;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationService {
  private final JdbcClient jdbc;
  public ReconciliationService(JdbcClient jdbc) { this.jdbc=jdbc; }

  @Transactional
  public void reconcileAll() {
    for(UUID org:jdbc.sql("SELECT id FROM organizations").query(UUID.class).list()) reconcile(org);
  }

  @Transactional
  public List<ReconciliationView> reconcile(UUID organizationId) {
    List<PaymentRow> payments=jdbc.sql("SELECT id,amount,refunded_amount FROM payments WHERE organization_id=:org ORDER BY created_at")
      .param("org",organizationId).query((rs,n)->new PaymentRow(rs.getObject("id",UUID.class),rs.getBigDecimal("amount"),rs.getBigDecimal("refunded_amount"))).list();
    List<ReconciliationView> result=new ArrayList<>();
    for(PaymentRow payment:payments) result.add(reconcilePayment(organizationId,payment));
    return result;
  }

  private ReconciliationView reconcilePayment(UUID org,PaymentRow payment) {
    BigDecimal expected=payment.amount().subtract(payment.refunded());
    LedgerState ledger=jdbc.sql("""
      SELECT count(DISTINCT t.id) FILTER (WHERE t.reference_type='PAYMENT') payment_transactions,
        COALESCE(sum(CASE WHEN a.code='CASH' AND e.side='DEBIT' THEN e.amount WHEN a.code='CASH' AND e.side='CREDIT' THEN -e.amount ELSE 0 END),0) ledger_amount
      FROM ledger_transactions t JOIN ledger_entries e ON e.transaction_id=t.id JOIN ledger_accounts a ON a.id=e.account_id
      WHERE t.organization_id=:org AND (t.reference_id=:payment OR t.reference_id IN (SELECT id FROM refunds WHERE payment_id=:payment))
      """).param("org",org).param("payment",payment.id()).query((rs,n)->new LedgerState(rs.getLong("payment_transactions"),rs.getBigDecimal("ledger_amount"))).single();
    String status=ledger.paymentTransactions()==0?"MISSING":ledger.amount().compareTo(expected)==0?"MATCHED":"MISMATCHED";
    String details=switch(status) { case "MISSING" -> "Payment has no ledger transaction"; case "MISMATCHED" -> "Expected net cash does not equal ledger net cash"; default -> "Payment and ledger agree"; };
    jdbc.sql("INSERT INTO reconciliation_results(payment_id,organization_id,status,expected_amount,ledger_amount,details) VALUES (:payment,:org,:status,:expected,:ledger,:details) ON CONFLICT(payment_id) DO UPDATE SET status=EXCLUDED.status,expected_amount=EXCLUDED.expected_amount,ledger_amount=EXCLUDED.ledger_amount,details=EXCLUDED.details,checked_at=now()")
      .param("payment",payment.id()).param("org",org).param("status",status).param("expected",expected).param("ledger",ledger.amount()).param("details",details).update();
    return new ReconciliationView(payment.id(),status,expected,ledger.amount(),details);
  }
  record PaymentRow(UUID id,BigDecimal amount,BigDecimal refunded) {}
  record LedgerState(long paymentTransactions,BigDecimal amount) {}
  public record ReconciliationView(UUID paymentId,String status,BigDecimal expectedAmount,BigDecimal ledgerAmount,String details) {}
}
