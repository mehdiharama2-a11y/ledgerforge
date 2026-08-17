package dev.ledgerforge.payment;
import java.math.BigDecimal;
import java.util.UUID;
public record RefundView(UUID id, UUID paymentId, BigDecimal amount, PaymentStatus paymentStatus) {}
