package dev.ledgerforge.payment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
  private final PaymentService payments;
  public PaymentController(PaymentService payments) { this.payments = payments; }

  @PostMapping @ResponseStatus(HttpStatus.CREATED)
  PaymentView capture(@RequestHeader("X-Organization-Id") UUID organizationId, @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody CaptureRequest request) {
    return payments.capture(organizationId, key, request.amount(), request.currency());
  }
  @PostMapping("/{paymentId}/refunds") @ResponseStatus(HttpStatus.CREATED)
  RefundView refund(@RequestHeader("X-Organization-Id") UUID organizationId, @RequestHeader("Idempotency-Key") String key, @PathVariable UUID paymentId, @Valid @RequestBody RefundRequest request) {
    return payments.refund(organizationId, paymentId, key, request.amount());
  }
  @GetMapping("/{paymentId}")
  PaymentView get(@RequestHeader("X-Organization-Id") UUID organizationId, @PathVariable UUID paymentId) { return payments.payment(organizationId, paymentId); }

  record CaptureRequest(@NotNull @DecimalMin("0.01") BigDecimal amount, @NotBlank String currency) {}
  record RefundRequest(@NotNull @DecimalMin("0.01") BigDecimal amount) {}
}
