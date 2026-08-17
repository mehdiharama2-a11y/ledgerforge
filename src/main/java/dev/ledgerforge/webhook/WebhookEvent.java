package dev.ledgerforge.webhook;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
public record WebhookEvent(String type,String paymentReference,BigDecimal amount,String currency,OffsetDateTime occurredAt) {}
