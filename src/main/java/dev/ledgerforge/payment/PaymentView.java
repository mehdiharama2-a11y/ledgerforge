package dev.ledgerforge.payment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentView(UUID id, UUID organizationId, BigDecimal amount, BigDecimal refundedAmount, String currency, PaymentStatus status, OffsetDateTime createdAt) {}
