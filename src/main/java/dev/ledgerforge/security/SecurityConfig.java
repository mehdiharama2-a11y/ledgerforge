package dev.ledgerforge.security;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

  @Bean JwtEncoder jwtEncoder(@Value("${ledgerforge.jwt-secret}") String secret) {
    SecretKey key = new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
    return new NimbusJwtEncoder(new ImmutableSecret<>(key));
  }

  @Bean JwtDecoder jwtDecoder(@Value("${ledgerforge.jwt-secret}") String secret) {
    SecretKey key = new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
    return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
  }

  @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
      .cors(Customizer.withDefaults())
      .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
      .authorizeHttpRequests(auth -> auth
        .requestMatchers("/actuator/health", "/api/auth/login", "/api/webhooks/**").permitAll()
        .anyRequest().authenticated())
      .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(JwtAuthorities.converter())))
      .build();
  }
  @Bean CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config=new CorsConfiguration();
    config.setAllowedOrigins(List.of("http://localhost:3002")); config.setAllowedMethods(List.of("GET","POST","OPTIONS")); config.setAllowedHeaders(List.of("Authorization","Content-Type","Idempotency-Key"));
    UrlBasedCorsConfigurationSource source=new UrlBasedCorsConfigurationSource(); source.registerCorsConfiguration("/**",config); return source;
  }
}
