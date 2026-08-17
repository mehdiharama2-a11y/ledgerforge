package dev.ledgerforge.ledger;

import java.math.BigDecimal;
import java.util.UUID;

public record PostingLine(UUID accountId, LedgerSide side, BigDecimal amount, String currency) {}
