package dev.ledgerforge.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class LedgerInvariant {
  private LedgerInvariant() {}

  public static List<PostingLine> validate(List<PostingLine> lines) {
    if (lines == null || lines.size() < 2) throw new IllegalArgumentException("A posting requires at least two lines");
    String currency = lines.getFirst().currency();
    BigDecimal debits = BigDecimal.ZERO.setScale(2);
    BigDecimal credits = BigDecimal.ZERO.setScale(2);
    for (PostingLine line : lines) {
      if (!currency.equals(line.currency())) throw new IllegalArgumentException("Mixed currencies are not supported");
      BigDecimal amount = line.amount().setScale(2, RoundingMode.UNNECESSARY);
      if (amount.signum() <= 0) throw new IllegalArgumentException("Posting amounts must be positive");
      if (line.side() == LedgerSide.DEBIT) debits = debits.add(amount); else credits = credits.add(amount);
    }
    if (debits.compareTo(credits) != 0) throw new IllegalArgumentException("Ledger transaction is not balanced");
    return lines;
  }
}
