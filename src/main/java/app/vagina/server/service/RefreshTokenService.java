package app.vagina.server.service;

import app.vagina.server.entity.RefreshToken;
import app.vagina.server.mapper.RefreshTokenMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class RefreshTokenService {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();
  private static final HexFormat HEX_FORMAT = HexFormat.of();

  @ConfigProperty(name = "vagina.auth.refresh-token.lifespan")
  Long refreshTokenLifespan;

  @Inject RefreshTokenMapper refreshTokenMapper;

  @Transactional
  public IssuedRefreshToken issueRefreshToken(Long userId) {
    String rawToken = randomOpaqueToken();
    String tokenFamily = UUID.randomUUID().toString();
    LocalDateTime now = LocalDateTime.now();

    RefreshToken refreshToken = new RefreshToken();
    refreshToken.setUserId(userId);
    refreshToken.setTokenHash(sha256(rawToken));
    refreshToken.setTokenFamily(tokenFamily);
    refreshToken.setIssuedAt(now);
    refreshToken.setExpiresAt(now.plusSeconds(refreshTokenLifespan));
    refreshToken.setRotatedAt(null);
    refreshToken.setRevokedAt(null);
    refreshToken.setLastUsedAt(null);
    refreshToken.setSysmeta(null);
    refreshToken.setCreatedAt(now);
    refreshToken.setUpdatedAt(now);
    refreshTokenMapper.insert(refreshToken);

    return new IssuedRefreshToken(rawToken, refreshToken);
  }

  @Transactional
  public Optional<RotateResult> rotateRefreshToken(String rawToken) {
    Optional<RefreshToken> currentOpt = refreshTokenMapper.findByTokenHash(sha256(rawToken));
    if (currentOpt.isEmpty()) {
      return Optional.empty();
    }

    RefreshToken current = currentOpt.get();
    LocalDateTime now = LocalDateTime.now();

    if (isRevoked(current) || isExpired(current)) {
      return Optional.empty();
    }

    if (current.getRotatedAt() != null) {
      revokeTokenFamily(current.getTokenFamily(), now, "rotated_token_reuse_detected");
      return Optional.empty();
    }

    current.setRotatedAt(now);
    current.setLastUsedAt(now);
    current.setUpdatedAt(now);
    refreshTokenMapper.update(current);

    String newRawToken = randomOpaqueToken();
    RefreshToken replacement = new RefreshToken();
    replacement.setUserId(current.getUserId());
    replacement.setTokenHash(sha256(newRawToken));
    replacement.setTokenFamily(current.getTokenFamily());
    replacement.setIssuedAt(now);
    replacement.setExpiresAt(now.plusSeconds(refreshTokenLifespan));
    replacement.setRotatedAt(null);
    replacement.setRevokedAt(null);
    replacement.setLastUsedAt(null);
    replacement.setSysmeta(current.getSysmeta());
    replacement.setCreatedAt(now);
    replacement.setUpdatedAt(now);
    refreshTokenMapper.insert(replacement);

    return Optional.of(new RotateResult(newRawToken, replacement));
  }

  @Transactional
  public void revokeRefreshToken(String rawToken) {
    Optional<RefreshToken> refreshTokenOpt = refreshTokenMapper.findByTokenHash(sha256(rawToken));
    if (refreshTokenOpt.isEmpty()) {
      return;
    }

    RefreshToken refreshToken = refreshTokenOpt.get();
    LocalDateTime now = LocalDateTime.now();
    refreshToken.setRevokedAt(now);
    refreshToken.setUpdatedAt(now);
    refreshTokenMapper.update(refreshToken);
  }

  public boolean isRefreshTokenValid(String rawToken) {
    Optional<RefreshToken> refreshTokenOpt = refreshTokenMapper.findByTokenHash(sha256(rawToken));
    if (refreshTokenOpt.isEmpty()) {
      return false;
    }

    RefreshToken refreshToken = refreshTokenOpt.get();
    return !isRevoked(refreshToken)
        && !isExpired(refreshToken)
        && refreshToken.getRotatedAt() == null;
  }

  private void revokeTokenFamily(String tokenFamily, LocalDateTime now, String reason) {
    List<RefreshToken> familyTokens = refreshTokenMapper.findByTokenFamily(tokenFamily);
    for (RefreshToken familyToken : familyTokens) {
      familyToken.setRevokedAt(now);
      familyToken.setUpdatedAt(now);
      if (familyToken.getSysmeta() == null || familyToken.getSysmeta().isBlank()) {
        familyToken.setSysmeta("{\"reason\":\"" + reason + "\"}");
      }
      refreshTokenMapper.update(familyToken);
    }
  }

  private boolean isRevoked(RefreshToken refreshToken) {
    return refreshToken.getRevokedAt() != null;
  }

  private boolean isExpired(RefreshToken refreshToken) {
    return refreshToken.getExpiresAt() == null
        || !refreshToken.getExpiresAt().isAfter(LocalDateTime.now());
  }

  private String randomOpaqueToken() {
    byte[] randomBytes = new byte[32];
    SECURE_RANDOM.nextBytes(randomBytes);
    return HEX_FORMAT.formatHex(randomBytes);
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HEX_FORMAT.formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  public record IssuedRefreshToken(String rawToken, RefreshToken persistedToken) {}

  public record RotateResult(String rawToken, RefreshToken persistedToken) {}
}
