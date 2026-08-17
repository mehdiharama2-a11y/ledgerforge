package dev.ledgerforge.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class PaymentServiceIntegrationTest {
  private static final UUID ORG = UUID.fromString("00000000-0000-4000-8000-000000000001");
  @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }
  @Autowired PaymentService payments;
  @Autowired JdbcClient jdbc;

  @Test void captureIsIdempotentAndBalanced() {
    PaymentView first = payments.capture(ORG, "capture-one", new BigDecimal("125.50"), "usd");
    PaymentView replay = payments.capture(ORG, "capture-one", new BigDecimal("125.50"), "USD");
    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(transactionCount(first.id(), "PAYMENT")).isEqualTo(1);
    assertBalanced(first.id(), "PAYMENT");
  }

  @Test void sameIdempotencyKeyRejectsDifferentPayload() {
    payments.capture(ORG, "capture-conflict", new BigDecimal("10.00"), "USD");
    assertThatThrownBy(() -> payments.capture(ORG, "capture-conflict", new BigDecimal("11.00"), "USD"))
      .isInstanceOf(IdempotencyConflictException.class);
  }

  @Test void databaseRejectsUnbalancedAndMutableJournalHistory() {
    PaymentView payment = payments.capture(ORG, "database-invariants", new BigDecimal("20.00"), "USD");
    UUID transactionId = jdbc.sql("SELECT id FROM ledger_transactions WHERE reference_id=:id").param("id", payment.id()).query(UUID.class).single();
    UUID accountId = jdbc.sql("SELECT id FROM ledger_accounts WHERE organization_id=:org AND code='CASH'").param("org", ORG).query(UUID.class).single();
    assertThatThrownBy(() -> jdbc.sql("INSERT INTO ledger_entries(organization_id,transaction_id,account_id,side,amount,currency) VALUES (:org,:tx,:account,'DEBIT',1.00,'USD')")
      .param("org", ORG).param("tx", transactionId).param("account", accountId).update()).isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> jdbc.sql("UPDATE ledger_entries SET amount=1.00 WHERE transaction_id=:tx").param("tx", transactionId).update())
      .isInstanceOf(DataAccessException.class).hasMessageContaining("ledger history is immutable");
  }

  @Test void partialRefundsPreserveHistoryAndBalance() {
    PaymentView payment = payments.capture(ORG, "partial-payment", new BigDecimal("100.00"), "USD");
    RefundView first = payments.refund(ORG, payment.id(), "refund-a", new BigDecimal("30.00"));
    RefundView second = payments.refund(ORG, payment.id(), "refund-b", new BigDecimal("70.00"));
    assertThat(first.paymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    assertThat(second.paymentStatus()).isEqualTo(PaymentStatus.REFUNDED);
    assertBalanced(first.id(), "REFUND");
    assertBalanced(second.id(), "REFUND");
    assertThat(jdbc.sql("SELECT count(*) FROM ledger_transactions WHERE organization_id=:org").param("org", ORG).query(Long.class).single()).isEqualTo(3);
  }

  @Test void concurrentRefundsCannotExceedPayment() throws Exception {
    PaymentView payment = payments.capture(ORG, "concurrent-payment", new BigDecimal("100.00"), "USD");
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      CountDownLatch ready = new CountDownLatch(2);
      CountDownLatch go = new CountDownLatch(1);
      Callable<Object> refundA = () -> attemptRefund(payment.id(), "concurrent-a", ready, go);
      Callable<Object> refundB = () -> attemptRefund(payment.id(), "concurrent-b", ready, go);
      Future<Object> a = executor.submit(refundA); Future<Object> b = executor.submit(refundB);
      ready.await(10, TimeUnit.SECONDS); go.countDown();
      var results = java.util.List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
      assertThat(results.stream().filter(RefundView.class::isInstance)).hasSize(1);
      assertThat(results.stream().filter(RefundConflictException.class::isInstance)).hasSize(1);
    }
    PaymentView current = payments.payment(ORG, payment.id());
    assertThat(current.refundedAmount()).isEqualByComparingTo("60.00");
  }

  private Object attemptRefund(UUID paymentId, String key, CountDownLatch ready, CountDownLatch go) throws InterruptedException {
    ready.countDown(); go.await();
    try { return payments.refund(ORG, paymentId, key, new BigDecimal("60.00")); }
    catch (RefundConflictException error) { return error; }
  }
  private long transactionCount(UUID referenceId, String type) {
    return jdbc.sql("SELECT count(*) FROM ledger_transactions WHERE reference_id=:id AND reference_type=:type").param("id", referenceId).param("type", type).query(Long.class).single();
  }
  private void assertBalanced(UUID referenceId, String type) {
    var totals = jdbc.sql("SELECT COALESCE(sum(amount) FILTER (WHERE side='DEBIT'),0) debits, COALESCE(sum(amount) FILTER (WHERE side='CREDIT'),0) credits FROM ledger_entries e JOIN ledger_transactions t ON t.id=e.transaction_id WHERE t.reference_id=:id AND t.reference_type=:type")
      .param("id", referenceId).param("type", type).query((rs, n) -> new BigDecimal[]{rs.getBigDecimal("debits"), rs.getBigDecimal("credits")}).single();
    assertThat(totals[0]).isEqualByComparingTo(totals[1]);
  }
}
