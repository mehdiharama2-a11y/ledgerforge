package dev.ledgerforge.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ledgerforge.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxScheduler {
  private final OutboxPublisher publisher;

  public OutboxScheduler(OutboxPublisher publisher) {
    this.publisher = publisher;
  }

  @Scheduled(fixedDelay = 1000)
  public void publishPending() {
    publisher.publishPending();
  }
}
