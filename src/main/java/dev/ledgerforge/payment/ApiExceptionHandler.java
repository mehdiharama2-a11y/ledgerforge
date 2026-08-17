package dev.ledgerforge.payment;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler({IdempotencyConflictException.class, RefundConflictException.class})
  @ResponseStatus(HttpStatus.CONFLICT)
  Map<String, String> conflict(RuntimeException error) { return Map.of("error", error.getMessage()); }
  @ExceptionHandler(PaymentNotFoundException.class) @ResponseStatus(HttpStatus.NOT_FOUND)
  Map<String, String> missing(RuntimeException error) { return Map.of("error", error.getMessage()); }
  @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
  Map<String, String> invalid(RuntimeException error) { return Map.of("error", error.getMessage()); }
}
