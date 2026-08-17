package dev.ledgerforge.ledger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerInvariantTest {
  @Test void rejectsUnbalancedPosting() {
    assertThatThrownBy(() -> LedgerInvariant.validate(List.of(
      new PostingLine(UUID.randomUUID(), LedgerSide.DEBIT, new BigDecimal("10.00"), "USD"),
      new PostingLine(UUID.randomUUID(), LedgerSide.CREDIT, new BigDecimal("9.99"), "USD"))))
      .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not balanced");
  }
  @Test void rejectsFractionalCentsInsteadOfRoundingMoney() {
    assertThatThrownBy(() -> LedgerInvariant.validate(List.of(
      new PostingLine(UUID.randomUUID(), LedgerSide.DEBIT, new BigDecimal("1.001"), "USD"),
      new PostingLine(UUID.randomUUID(), LedgerSide.CREDIT, new BigDecimal("1.001"), "USD"))))
      .isInstanceOf(ArithmeticException.class);
  }
}
