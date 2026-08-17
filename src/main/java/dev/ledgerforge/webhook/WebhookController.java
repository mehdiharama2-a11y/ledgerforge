package dev.ledgerforge.webhook;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {
  private final WebhookService webhooks;
  public WebhookController(WebhookService webhooks) { this.webhooks=webhooks; }
  @PostMapping("/{organizationSlug}") @ResponseStatus(HttpStatus.ACCEPTED)
  WebhookService.WebhookResult receive(@PathVariable String organizationSlug,@RequestHeader("X-Webhook-Id") String eventId,@RequestHeader("X-Webhook-Timestamp") long timestamp,@RequestHeader("X-Webhook-Signature") String signature,@RequestBody String payload) {
    return webhooks.receive(organizationSlug,eventId,timestamp,signature,payload);
  }
}
