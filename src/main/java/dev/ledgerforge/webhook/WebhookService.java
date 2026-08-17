package dev.ledgerforge.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledgerforge.payment.PaymentService;
import dev.ledgerforge.payment.PaymentView;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WebhookService {
  private final JdbcClient jdbc; private final ObjectMapper json; private final PaymentService payments; private final long tolerance;
  public WebhookService(JdbcClient jdbc,ObjectMapper json,PaymentService payments,@Value("${ledgerforge.webhook-tolerance-seconds}") long tolerance) { this.jdbc=jdbc;this.json=json;this.payments=payments;this.tolerance=tolerance; }

  @Transactional
  public WebhookResult receive(String organizationSlug,String eventId,long timestamp,String signature,String rawPayload) {
    Organization org=jdbc.sql("SELECT id,webhook_secret FROM organizations WHERE slug=:slug").param("slug",organizationSlug)
      .query((rs,n)->new Organization(rs.getObject("id",UUID.class),rs.getString("webhook_secret"))).optional()
      .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Organization not found"));
    if (Math.abs(Instant.now().getEpochSecond()-timestamp)>tolerance) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Webhook timestamp outside tolerance");
    if (!WebhookSignature.matches(WebhookSignature.sign(org.secret(),timestamp,rawPayload),signature)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid webhook signature");
    WebhookEvent event;
    try { event=json.readValue(rawPayload,WebhookEvent.class); } catch(Exception error) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid webhook payload"); }
    int inserted=jdbc.sql("INSERT INTO webhook_receipts(organization_id,provider_event_id,event_type,payment_reference,occurred_at,status,payload) VALUES (:org,:id,:type,:ref,:occurred,'PENDING',CAST(:payload AS jsonb)) ON CONFLICT (organization_id,provider_event_id) DO NOTHING")
      .param("org",org.id()).param("id",eventId).param("type",event.type()).param("ref",event.paymentReference()).param("occurred",event.occurredAt()).param("payload",rawPayload).update();
    if(inserted==0) {
      String status=jdbc.sql("SELECT status FROM webhook_receipts WHERE organization_id=:org AND provider_event_id=:id").param("org",org.id()).param("id",eventId).query(String.class).single();
      return new WebhookResult(status,true,null);
    }
    UUID paymentId=process(org.id(),eventId,event);
    return new WebhookResult(paymentId==null?"PENDING":"PROCESSED",false,paymentId);
  }

  private UUID process(UUID organizationId,String eventId,WebhookEvent event) {
    if("payment.captured".equals(event.type())) {
      PaymentView payment=payments.capture(organizationId,"webhook:"+event.paymentReference(),event.amount(),event.currency());
      markProcessed(organizationId,eventId);
      processPendingRefunds(organizationId,event.paymentReference(),payment.id());
      return payment.id();
    }
    if("payment.refunded".equals(event.type())) {
      Optional<UUID> paymentId=findPayment(organizationId,event.paymentReference());
      if(paymentId.isEmpty()) return null;
      payments.refund(organizationId,paymentId.get(),"webhook:"+eventId,event.amount());
      markProcessed(organizationId,eventId); return paymentId.get();
    }
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported webhook event type");
  }
  private void processPendingRefunds(UUID org,String reference,UUID paymentId) {
    var pending=jdbc.sql("SELECT provider_event_id,payload::text FROM webhook_receipts WHERE organization_id=:org AND payment_reference=:ref AND event_type='payment.refunded' AND status='PENDING' ORDER BY occurred_at")
      .param("org",org).param("ref",reference).query((rs,n)->Map.entry(rs.getString(1),rs.getString(2))).list();
    for(var row:pending) try { WebhookEvent event=json.readValue(row.getValue(),WebhookEvent.class); payments.refund(org,paymentId,"webhook:"+row.getKey(),event.amount()); markProcessed(org,row.getKey()); } catch(Exception error) { throw new IllegalStateException("Unable to process pending refund",error); }
  }
  private Optional<UUID> findPayment(UUID org,String reference) {
    return jdbc.sql("SELECT id FROM payments WHERE organization_id=:org AND idempotency_key=:key").param("org",org).param("key","webhook:"+reference).query(UUID.class).optional();
  }
  private void markProcessed(UUID org,String eventId) { jdbc.sql("UPDATE webhook_receipts SET status='PROCESSED',processed_at=now() WHERE organization_id=:org AND provider_event_id=:id").param("org",org).param("id",eventId).update(); }
  record Organization(UUID id,String secret) {}
  public record WebhookResult(String status,boolean duplicate,UUID paymentId) {}
}
