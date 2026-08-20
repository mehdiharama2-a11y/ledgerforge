package dev.ledgerforge.webhook;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/api/webhooks")
@Validated
public class WebhookController {
  private final WebhookService webhooks;
  public WebhookController(WebhookService webhooks) { this.webhooks=webhooks; }
  @PostMapping("/{organizationSlug}") @ResponseStatus(HttpStatus.ACCEPTED)
  WebhookService.WebhookResult receive(@PathVariable @Size(max=64) @Pattern(regexp="^[a-z0-9][a-z0-9-]*$") String organizationSlug,@RequestHeader("X-Webhook-Id") @Size(min=1,max=128) String eventId,@RequestHeader("X-Webhook-Timestamp") long timestamp,@RequestHeader("X-Webhook-Signature") @Pattern(regexp="^[0-9a-fA-F]{64}$") String signature,@RequestBody @Size(max=262144) String payload) {
    return webhooks.receive(organizationSlug,eventId,timestamp,signature,payload);
  }
}
