package dev.ledgerforge.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledgerforge.outbox.OutboxPublisher;
import dev.ledgerforge.ledger.*;
import dev.ledgerforge.reconciliation.ReconciliationService;
import dev.ledgerforge.webhook.WebhookService;
import dev.ledgerforge.webhook.WebhookSignature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "ledgerforge.scheduling-enabled=false")
@Testcontainers
@AutoConfigureMockMvc
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
  @Autowired WebhookService webhooks;
  @Autowired OutboxPublisher publisher;
  @Autowired ReconciliationService reconciliation;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired LedgerService ledger;

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
    assertThat(jdbc.sql("SELECT count(*) FROM ledger_transactions WHERE organization_id=:org AND (reference_id=:payment OR reference_id IN (SELECT id FROM refunds WHERE payment_id=:payment))")
      .param("org", ORG).param("payment",payment.id()).query(Long.class).single()).isEqualTo(3);
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

  @Test void signedWebhooksHandleDuplicatesAndOutOfOrderRefunds() throws Exception {
    long timestamp=java.time.Instant.now().getEpochSecond();
    String reference="provider-order-42";
    String refundPayload=json.writeValueAsString(new dev.ledgerforge.webhook.WebhookEvent("payment.refunded",reference,new BigDecimal("25.00"),"USD",java.time.OffsetDateTime.now().minusSeconds(5)));
    var pending=webhooks.receive("demo","wh-refund-first",timestamp,WebhookSignature.sign("whsec_demo_ledgerforge",timestamp,refundPayload),refundPayload);
    assertThat(pending.status()).isEqualTo("PENDING");
    String capturePayload=json.writeValueAsString(new dev.ledgerforge.webhook.WebhookEvent("payment.captured",reference,new BigDecimal("100.00"),"USD",java.time.OffsetDateTime.now().minusSeconds(10)));
    var captured=webhooks.receive("demo","wh-capture-second",timestamp,WebhookSignature.sign("whsec_demo_ledgerforge",timestamp,capturePayload),capturePayload);
    assertThat(captured.status()).isEqualTo("PROCESSED");
    var duplicate=webhooks.receive("demo","wh-capture-second",timestamp,WebhookSignature.sign("whsec_demo_ledgerforge",timestamp,capturePayload),capturePayload);
    assertThat(duplicate.duplicate()).isTrue();
    assertThat(payments.payment(ORG,captured.paymentId()).refundedAmount()).isEqualByComparingTo("25.00");
    assertThat(jdbc.sql("SELECT status FROM webhook_receipts WHERE provider_event_id='wh-refund-first'").query(String.class).single()).isEqualTo("PROCESSED");
    assertThatThrownBy(()->webhooks.receive("demo","bad-signature",timestamp,"invalid",capturePayload)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("Invalid webhook signature");
  }

  @Test void outboxSurvivesTransactionAndReconciliationMatchesLedger() {
    PaymentView payment=payments.capture(ORG,"outbox-proof",new BigDecimal("44.00"),"USD");
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE aggregate_id=:id").param("id",payment.id()).query(Long.class).single()).isEqualTo(1);
    publisher.publishPending();
    assertThat(jdbc.sql("SELECT count(*) FROM published_events p JOIN outbox_events o ON o.id=p.outbox_id WHERE o.aggregate_id=:id").param("id",payment.id()).query(Long.class).single()).isEqualTo(1);
    assertThat(reconciliation.reconcile(ORG)).anyMatch(row->row.paymentId().equals(payment.id())&&row.status().equals("MATCHED"));
  }

  @Test void outboxRetriesPersistedFailureWithoutLosingEvent() {
    UUID id=UUID.randomUUID();
    jdbc.sql("INSERT INTO outbox_events(id,organization_id,aggregate_type,aggregate_id,event_type,payload,failures_remaining) VALUES (:id,:org,'PAYMENT',:aggregate,'failure.drill','{}',1)")
      .param("id",id).param("org",ORG).param("aggregate",UUID.randomUUID()).update();
    publisher.publishPending();
    assertThat(jdbc.sql("SELECT status FROM outbox_events WHERE id=:id").param("id",id).query(String.class).single()).isEqualTo("FAILED");
    assertThat(jdbc.sql("SELECT count(*) FROM published_events WHERE outbox_id=:id").param("id",id).query(Long.class).single()).isZero();
    jdbc.sql("UPDATE outbox_events SET available_at=now() WHERE id=:id").param("id",id).update();
    publisher.publishPending();
    assertThat(jdbc.sql("SELECT status FROM outbox_events WHERE id=:id").param("id",id).query(String.class).single()).isEqualTo("PUBLISHED");
    assertThat(jdbc.sql("SELECT count(*) FROM published_events WHERE outbox_id=:id").param("id",id).query(Long.class).single()).isEqualTo(1);
  }

  @Test void reconciliationClassifiesMissingAndMismatchedPayments() {
    UUID missing=UUID.randomUUID(),mismatched=UUID.randomUUID();
    jdbc.sql("INSERT INTO payments(id,organization_id,idempotency_key,amount,currency,status) VALUES (:id,:org,'recon-missing',10.00,'USD','CAPTURED')").param("id",missing).param("org",ORG).update();
    jdbc.sql("INSERT INTO payments(id,organization_id,idempotency_key,amount,currency,status) VALUES (:id,:org,'recon-mismatch',10.00,'USD','CAPTURED')").param("id",mismatched).param("org",ORG).update();
    UUID cash=ledger.accountId(ORG,"CASH"),payable=ledger.accountId(ORG,"MERCHANT_PAYABLE");
    ledger.post(ORG,"PAYMENT",mismatched,"Intentionally mismatched business amount",java.util.List.of(new PostingLine(cash,LedgerSide.DEBIT,new BigDecimal("9.00"),"USD"),new PostingLine(payable,LedgerSide.CREDIT,new BigDecimal("9.00"),"USD")));
    var rows=reconciliation.reconcile(ORG);
    assertThat(rows).anyMatch(row->row.paymentId().equals(missing)&&row.status().equals("MISSING"));
    assertThat(rows).anyMatch(row->row.paymentId().equals(mismatched)&&row.status().equals("MISMATCHED"));
  }

  @Test void auditorCannotCreatePaymentsAndTenantReportsDoNotLeak() throws Exception {
    String auditor=login("demo","auditor@ledgerforge.dev","LedgerForge123!");
    mvc.perform(post("/api/payments").header("Authorization","Bearer "+auditor).header("Idempotency-Key","forbidden").contentType("application/json").content("{\"amount\":10.00,\"currency\":\"USD\"}"))
      .andExpect(status().isForbidden());
    UUID acme=UUID.fromString("00000000-0000-4000-8000-000000000011");
    PaymentView acmePayment=payments.capture(acme,"acme-private",new BigDecimal("77.00"),"USD");
    String admin=login("demo","admin@ledgerforge.dev","LedgerForge123!");
    mvc.perform(get("/api/reports/payments").header("Authorization","Bearer "+admin)).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(acmePayment.id().toString()))));
  }

  private String login(String organization,String email,String password) throws Exception {
    String response=mvc.perform(post("/api/auth/login").contentType("application/json").content(json.writeValueAsString(java.util.Map.of("organizationSlug",organization,"email",email,"password",password))))
      .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    return json.readTree(response).get("accessToken").asText();
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
