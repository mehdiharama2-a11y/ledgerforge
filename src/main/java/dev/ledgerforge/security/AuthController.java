package dev.ledgerforge.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final JdbcClient jdbc; private final PasswordEncoder passwords; private final JwtEncoder encoder;
  public AuthController(JdbcClient jdbc, PasswordEncoder passwords, JwtEncoder encoder) { this.jdbc=jdbc; this.passwords=passwords; this.encoder=encoder; }

  @PostMapping("/login")
  LoginResponse login(@Valid @RequestBody LoginRequest request) {
    UserRecord user = jdbc.sql("SELECT u.id,u.organization_id,u.email,u.password_hash,u.role FROM users u JOIN organizations o ON o.id=u.organization_id WHERE o.slug=:slug AND u.email=:email")
      .param("slug", request.organizationSlug()).param("email", request.email().toLowerCase()).query((rs,n) -> new UserRecord(rs.getObject("id",UUID.class),rs.getObject("organization_id",UUID.class),rs.getString("email"),rs.getString("password_hash"),rs.getString("role"))).optional()
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid credentials"));
    if (!passwords.matches(request.password(), user.passwordHash())) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid credentials");
    Instant now=Instant.now();
    JwtClaimsSet claims=JwtClaimsSet.builder().issuer("ledgerforge").issuedAt(now).expiresAt(now.plus(30, ChronoUnit.MINUTES)).subject(user.id().toString())
      .claim("organizationId",user.organizationId().toString()).claim("email",user.email()).claim("role",user.role()).build();
    String token=encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),claims)).getTokenValue();
    return new LoginResponse(token, Map.of("email",user.email(),"role",user.role(),"organizationId",user.organizationId()));
  }
  record LoginRequest(@NotBlank @Size(max=64) @Pattern(regexp="^[a-z0-9][a-z0-9-]*$") String organizationSlug,@Email @Size(max=254) String email,@NotBlank @Size(min=8,max=128) String password) {}
  record LoginResponse(String accessToken, Map<String,Object> user) {}
  record UserRecord(UUID id,UUID organizationId,String email,String passwordHash,String role) {}
}
