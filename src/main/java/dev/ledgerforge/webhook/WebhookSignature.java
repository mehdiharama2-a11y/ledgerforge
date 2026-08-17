package dev.ledgerforge.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class WebhookSignature {
  private WebhookSignature() {}
  public static String sign(String secret,long timestamp,String payload) {
    try {
      Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal((timestamp+"."+payload).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) { throw new IllegalStateException("Unable to sign webhook",error); }
  }
  public static boolean matches(String expected,String provided) {
    if (provided==null) return false;
    return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),provided.getBytes(StandardCharsets.US_ASCII));
  }
}
