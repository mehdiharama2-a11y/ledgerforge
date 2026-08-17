package dev.ledgerforge.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import dev.ledgerforge.security.TenantContext;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
  private final PaymentService payments;
  public PaymentController(PaymentService payments) { this.payments = payments; }

  @PostMapping @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
  PaymentView capture(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CaptureRequest request) {
    return payments.capture(TenantContext.organizationId(jwt), key, request.amount(), request.currency());
  }
  @PostMapping("/{paymentId}/refunds") @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
  RefundView refund(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key, @PathVariable UUID paymentId, @Valid @RequestBody RefundRequest request) {
    return payments.refund(TenantContext.organizationId(jwt), paymentId, key, request.amount());
  }
  @GetMapping("/{paymentId}")
  PaymentView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID paymentId) { return payments.payment(TenantContext.organizationId(jwt), paymentId); }

  record CaptureRequest(@NotNull @DecimalMin("0.01") BigDecimal amount, @NotBlank String currency) {}
  record RefundRequest(@NotNull @DecimalMin("0.01") BigDecimal amount) {}
}
