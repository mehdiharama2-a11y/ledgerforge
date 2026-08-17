package dev.ledgerforge.reporting;

import dev.ledgerforge.reconciliation.ReconciliationService;
import dev.ledgerforge.security.TenantContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ReportingController {
  private final JdbcClient jdbc; private final ReconciliationService reconciliation;
  public ReportingController(JdbcClient jdbc,ReconciliationService reconciliation) { this.jdbc=jdbc;this.reconciliation=reconciliation; }

  @GetMapping("/reports/payments")
  List<Map<String,Object>> payments(@AuthenticationPrincipal Jwt jwt) {
    return jdbc.sql("SELECT id,amount,refunded_amount,currency,status,created_at FROM payments WHERE organization_id=:org ORDER BY created_at DESC LIMIT 200")
      .param("org",TenantContext.organizationId(jwt)).query((rs,n)->map("id",rs.getObject("id",UUID.class),"amount",rs.getBigDecimal("amount"),"refundedAmount",rs.getBigDecimal("refunded_amount"),"currency",rs.getString("currency").trim(),"status",rs.getString("status"),"createdAt",rs.getObject("created_at",OffsetDateTime.class))).list();
  }
  @GetMapping("/reports/payments/{id}/history")
  Map<String,Object> history(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {
    UUID org=TenantContext.organizationId(jwt);
    Long visible=jdbc.sql("SELECT count(*) FROM payments WHERE organization_id=:org AND id=:id").param("org",org).param("id",id).query(Long.class).single();
    if(visible==0) throw new dev.ledgerforge.payment.PaymentNotFoundException(id);
    var entries=jdbc.sql("""
      SELECT t.id transaction_id,t.reference_type,t.description,t.created_at,a.code,e.side,e.amount,e.currency
      FROM ledger_transactions t JOIN ledger_entries e ON e.transaction_id=t.id JOIN ledger_accounts a ON a.id=e.account_id
      WHERE t.organization_id=:org AND (t.reference_id=:payment OR t.reference_id IN (SELECT id FROM refunds WHERE organization_id=:org AND payment_id=:payment))
      ORDER BY t.created_at,e.id
      """).param("org",org).param("payment",id).query((rs,n)->map("transactionId",rs.getObject("transaction_id",UUID.class),"referenceType",rs.getString("reference_type"),"description",rs.getString("description"),"createdAt",rs.getObject("created_at",OffsetDateTime.class),"account",rs.getString("code"),"side",rs.getString("side"),"amount",rs.getBigDecimal("amount"),"currency",rs.getString("currency").trim())).list();
    var refunds=jdbc.sql("SELECT id,amount,created_at FROM refunds WHERE organization_id=:org AND payment_id=:payment ORDER BY created_at").param("org",org).param("payment",id)
      .query((rs,n)->map("id",rs.getObject("id",UUID.class),"amount",rs.getBigDecimal("amount"),"createdAt",rs.getObject("created_at",OffsetDateTime.class))).list();
    return Map.of("entries",entries,"refunds",refunds);
  }
  @GetMapping("/reports/balances")
  List<Map<String,Object>> balances(@AuthenticationPrincipal Jwt jwt) {
    return jdbc.sql("""
      SELECT a.code,a.name,a.account_type,a.currency,COALESCE(sum(e.amount) FILTER(WHERE e.side='DEBIT'),0) debits,COALESCE(sum(e.amount) FILTER(WHERE e.side='CREDIT'),0) credits
      FROM ledger_accounts a LEFT JOIN ledger_entries e ON e.account_id=a.id WHERE a.organization_id=:org GROUP BY a.id ORDER BY a.code
      """).param("org",TenantContext.organizationId(jwt)).query((rs,n)->{
        BigDecimal debits=rs.getBigDecimal("debits"),credits=rs.getBigDecimal("credits"); String type=rs.getString("account_type");
        return map("code",rs.getString("code"),"name",rs.getString("name"),"accountType",type,"currency",rs.getString("currency").trim(),"debits",debits,"credits",credits,"balance",("ASSET".equals(type)||"EXPENSE".equals(type))?debits.subtract(credits):credits.subtract(debits));
      }).list();
  }
  @PostMapping("/reconciliation/run") @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
  List<ReconciliationService.ReconciliationView> run(@AuthenticationPrincipal Jwt jwt) { return reconciliation.reconcile(TenantContext.organizationId(jwt)); }
  @GetMapping("/reconciliation")
  List<Map<String,Object>> reconciliation(@AuthenticationPrincipal Jwt jwt) {
    return jdbc.sql("SELECT payment_id,status,expected_amount,ledger_amount,details,checked_at FROM reconciliation_results WHERE organization_id=:org ORDER BY checked_at DESC")
      .param("org",TenantContext.organizationId(jwt)).query((rs,n)->map("paymentId",rs.getObject("payment_id",UUID.class),"status",rs.getString("status"),"expectedAmount",rs.getBigDecimal("expected_amount"),"ledgerAmount",rs.getBigDecimal("ledger_amount"),"details",rs.getString("details"),"checkedAt",rs.getObject("checked_at",OffsetDateTime.class))).list();
  }
  private static Map<String,Object> map(Object... values) { Map<String,Object> result=new LinkedHashMap<>(); for(int i=0;i<values.length;i+=2) result.put((String)values[i],values[i+1]); return result; }
}
