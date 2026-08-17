package dev.ledgerforge.payment;
public class IdempotencyConflictException extends RuntimeException { public IdempotencyConflictException(String message) { super(message); } }
