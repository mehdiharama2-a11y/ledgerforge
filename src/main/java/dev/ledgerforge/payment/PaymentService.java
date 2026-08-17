package dev.ledgerforge.payment;

import dev.ledgerforge.ledger.LedgerService;
import dev.ledgerforge.ledger.LedgerSide;
import dev.ledgerforge.ledger.PostingLine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {
  private final JdbcClient jdbc;
  private final LedgerService ledger;
  public PaymentService(JdbcClient jdbc, LedgerService ledger) { this.jdbc = jdbc; this.ledger = ledger; }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public PaymentView capture(UUID organizationId, String idempotencyKey, BigDecimal rawAmount, String rawCurrency) {
    BigDecimal amount = money(rawAmount);
    String currency = currency(rawCurrency);
    UUID paymentId = UUID.randomUUID();
    int inserted = jdbc.sql("INSERT INTO payments(id,organization_id,idempotency_key,amount,currency,status) VALUES (:id,:org,:key,:amount,:currency,'CAPTURED') ON CONFLICT (organization_id,idempotency_key) DO NOTHING")
      .params(Map.of("id", paymentId, "org", organizationId, "key", idempotencyKey, "amount", amount, "currency", currency)).update();
    if (inserted == 0) {
      PaymentView existing = paymentByKey(organizationId, idempotencyKey);
      if (existing.amount().compareTo(amount) != 0 || !existing.currency().equals(currency)) throw new IdempotencyConflictException("Idempotency key already used with a different payload");
      return existing;
    }
    UUID cash = ledger.accountId(organizationId, "CASH");
    UUID payable = ledger.accountId(organizationId, "MERCHANT_PAYABLE");
    ledger.post(organizationId, "PAYMENT", paymentId, "Payment capture", List.of(
      new PostingLine(cash, LedgerSide.DEBIT, amount, currency),
      new PostingLine(payable, LedgerSide.CREDIT, amount, currency)));
    return payment(organizationId, paymentId, false);
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public RefundView refund(UUID organizationId, UUID paymentId, String idempotencyKey, BigDecimal rawAmount) {
    BigDecimal amount = money(rawAmount);
    PaymentView payment = payment(organizationId, paymentId, true);
    var existing = jdbc.sql("SELECT id,amount FROM refunds WHERE organization_id=:org AND idempotency_key=:key")
      .param("org", organizationId).param("key", idempotencyKey).query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getBigDecimal("amount"))).optional();
    if (existing.isPresent()) {
      if (existing.get().getValue().compareTo(amount) != 0) throw new IdempotencyConflictException("Idempotency key already used with a different payload");
      return new RefundView(existing.get().getKey(), paymentId, amount, payment.status());
    }
    if (payment.refundedAmount().add(amount).compareTo(payment.amount()) > 0) throw new RefundConflictException("Refund exceeds the remaining payment amount");
    UUID refundId = UUID.randomUUID();
    try {
      jdbc.sql("INSERT INTO refunds(id,organization_id,payment_id,idempotency_key,amount) VALUES (:id,:org,:payment,:key,:amount)")
        .params(Map.of("id", refundId, "org", organizationId, "payment", paymentId, "key", idempotencyKey, "amount", amount)).update();
    } catch (DuplicateKeyException error) { throw new IdempotencyConflictException("Duplicate refund idempotency key"); }
    BigDecimal refunded = payment.refundedAmount().add(amount);
    PaymentStatus status = refunded.compareTo(payment.amount()) == 0 ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
    jdbc.sql("UPDATE payments SET refunded_amount=:refunded,status=:status,updated_at=now() WHERE id=:id")
      .params(Map.of("refunded", refunded, "status", status.name(), "id", paymentId)).update();
    UUID cash = ledger.accountId(organizationId, "CASH");
    UUID payable = ledger.accountId(organizationId, "MERCHANT_PAYABLE");
    ledger.post(organizationId, "REFUND", refundId, "Payment refund", List.of(
      new PostingLine(payable, LedgerSide.DEBIT, amount, payment.currency()),
      new PostingLine(cash, LedgerSide.CREDIT, amount, payment.currency())));
    return new RefundView(refundId, paymentId, amount, status);
  }

  public PaymentView payment(UUID organizationId, UUID id) { return payment(organizationId, id, false); }
  private PaymentView paymentByKey(UUID organizationId, String key) {
    return jdbc.sql("SELECT * FROM payments WHERE organization_id=:org AND idempotency_key=:key")
      .param("org", organizationId).param("key", key).query(this::mapPayment).single();
  }
  private PaymentView payment(UUID organizationId, UUID id, boolean lock) {
    return jdbc.sql("SELECT * FROM payments WHERE organization_id=:org AND id=:id" + (lock ? " FOR UPDATE" : ""))
      .param("org", organizationId).param("id", id).query(this::mapPayment).optional().orElseThrow(() -> new PaymentNotFoundException(id));
  }
  private PaymentView mapPayment(ResultSet rs, int row) throws SQLException {
    return new PaymentView(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class), rs.getBigDecimal("amount"), rs.getBigDecimal("refunded_amount"), rs.getString("currency").trim(), PaymentStatus.valueOf(rs.getString("status")), rs.getObject("created_at", OffsetDateTime.class));
  }
  private static BigDecimal money(BigDecimal amount) {
    if (amount == null) throw new IllegalArgumentException("Amount is required");
    BigDecimal normalized = amount.setScale(2, RoundingMode.UNNECESSARY);
    if (normalized.signum() <= 0) throw new IllegalArgumentException("Amount must be positive");
    return normalized;
  }
  private static String currency(String value) {
    String normalized = value == null ? "" : value.trim().toUpperCase();
    if (!normalized.matches("[A-Z]{3}")) throw new IllegalArgumentException("Currency must be an ISO-style three-letter code");
    return normalized;
  }
}
