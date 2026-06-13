package app.vagina.server.service.oidcprovider;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.StringJoiner;

public abstract class OidcProviderBase {

  public record OidcTokenSet(String accessToken, String idToken, long expiresIn) {}

  public record OidcUserInfo(
      String subject,
      String providerLogin,
      String displayName,
      String avatarUrl,
      String email,
      boolean emailVerified,
      String rawProfileJson) {}

  public abstract String getProviderKey();

  public abstract String buildAuthorizationUrl(
      String redirectUri, String state, String codeChallenge, String codeChallengeMethod);

  public abstract OidcTokenSet exchangeAuthorizationCode(
      String code, String redirectUri, String codeVerifier);

  public abstract OidcUserInfo fetchUserInfo(String accessToken);

  protected String formEncode(Map<String, String> values) {
    StringJoiner joiner = new StringJoiner("&");
    values.forEach(
        (key, value) ->
            joiner.add(
                URLEncoder.encode(key, StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(value, StandardCharsets.UTF_8)));
    return joiner.toString();
  }

  protected String requiredText(JsonNode node, String fieldName) {
    String value = optionalText(node, fieldName);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing required OIDC field: " + fieldName);
    }
    return value;
  }

  protected String optionalText(JsonNode node, String fieldName) {
    JsonNode child = node.get(fieldName);
    if (child == null || child.isNull()) {
      return null;
    }
    String value = child.asText();
    return value == null || value.isBlank() ? null : value;
  }
}
