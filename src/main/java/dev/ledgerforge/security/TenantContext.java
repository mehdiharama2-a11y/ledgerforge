package dev.ledgerforge.security;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

public final class TenantContext {
  private TenantContext() {}
  public static UUID organizationId(Jwt jwt) { return UUID.fromString(jwt.getClaimAsString("organizationId")); }
}
