package dev.ledgerforge.security;

import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

final class JwtAuthorities {
  private JwtAuthorities() {}
  static Converter<Jwt, AbstractAuthenticationToken> converter() {
    return jwt -> new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role"))), jwt.getSubject());
  }
}
