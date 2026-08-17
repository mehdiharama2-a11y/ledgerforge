package dev.ledgerforge.reconciliation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "ledgerforge.scheduling-enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationScheduler {
  private final ReconciliationService reconciliation;

  public ReconciliationScheduler(ReconciliationService reconciliation) {
    this.reconciliation = reconciliation;
  }

  @Scheduled(fixedDelay = 5000)
  public void reconcileAll() {
    reconciliation.reconcileAll();
  }
}
