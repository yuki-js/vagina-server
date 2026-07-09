package app.vagina.server.service;

import app.vagina.server.domain.error.NotFoundException;
import app.vagina.server.domain.error.ValidationException;
import app.vagina.server.entity.EntitlementDefinition;
import app.vagina.server.entity.EntitlementGrantSource;
import app.vagina.server.entity.UserEntitlementGrant;
import app.vagina.server.mapper.EntitlementMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class EntitlementService {

  @Inject EntitlementMapper entitlementMapper;

  public List<String> listActiveEntitlementKeys(Long userId) {
    if (userId == null) {
      throw new ValidationException("userId is required");
    }
    return entitlementMapper.findActiveEntitlementKeys(userId, LocalDateTime.now());
  }

  public boolean hasActiveEntitlement(Long userId, String entitlementKey) {
    String normalizedKey = normalizeEntitlementKey(entitlementKey);
    return listActiveEntitlementKeys(userId).contains(normalizedKey);
  }

  public Optional<EntitlementDefinition> findDefinitionByKey(String entitlementKey) {
    return entitlementMapper
        .findDefinitionByKey(normalizeEntitlementKey(entitlementKey))
        .map(this::toEntitlementDefinitionDomain);
  }

  @Transactional
  public EntitlementDefinition ensureDefinition(
      String entitlementKey, String displayName, String description) {
    String normalizedKey = normalizeEntitlementKey(entitlementKey);
    Optional<EntitlementDefinition> existing =
        entitlementMapper
            .findDefinitionByKey(normalizedKey)
            .map(this::toEntitlementDefinitionDomain);
    if (existing.isPresent()) {
      return existing.get();
    }

    LocalDateTime now = LocalDateTime.now();
    EntitlementDefinition definition =
        new EntitlementDefinition(
            null,
            normalizedKey,
            normalizeDisplayName(displayName, normalizedKey),
            description,
            true,
            null,
            now,
            now);
    EntitlementMapper.DefinitionRow row = toDefinitionRow(definition);
    try {
      entitlementMapper.insertDefinition(row);
    } catch (RuntimeException e) {
      if (!isUniqueConstraintViolation(e)) {
        throw e;
      }
      return entitlementMapper
          .findDefinitionByKey(normalizedKey)
          .map(this::toEntitlementDefinitionDomain)
          .orElseThrow(() -> e);
    }
    definition.setGeneratedId(row.getId());
    return definition;
  }

  @Transactional
  public UserEntitlementGrant grantEntitlement(
      Long userId,
      String entitlementKey,
      EntitlementGrantSource grantSource,
      LocalDateTime validFrom,
      LocalDateTime expiresAt,
      String grantReason) {
    if (userId == null) {
      throw new ValidationException("userId is required");
    }
    EntitlementGrantSource normalizedSource =
        grantSource == null ? EntitlementGrantSource.MANUAL : grantSource;
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime normalizedValidFrom = validFrom == null ? now : validFrom;
    if (expiresAt != null && !expiresAt.isAfter(normalizedValidFrom)) {
      throw new ValidationException("expiresAt must be after validFrom");
    }

    EntitlementDefinition definition =
        findDefinitionByKey(entitlementKey)
            .orElseThrow(
                () -> new NotFoundException("Entitlement definition not found: " + entitlementKey));
    if (!definition.isEnabled()) {
      throw new ValidationException("Entitlement definition is disabled: " + entitlementKey);
    }

    UserEntitlementGrant grant =
        new UserEntitlementGrant(
            null,
            userId,
            definition.getId(),
            normalizedSource,
            normalizedValidFrom,
            expiresAt,
            null,
            grantReason,
            null,
            null,
            now,
            now);
    EntitlementMapper.GrantRow row = toGrantRow(grant);
    entitlementMapper.insertGrant(row);
    grant.setGeneratedId(row.getId());
    return grant;
  }

  @Transactional
  public boolean revokeEntitlement(Long userId, String entitlementKey, String revokeReason) {
    if (userId == null) {
      throw new ValidationException("userId is required");
    }
    EntitlementDefinition definition =
        findDefinitionByKey(entitlementKey)
            .orElseThrow(
                () -> new NotFoundException("Entitlement definition not found: " + entitlementKey));
    LocalDateTime now = LocalDateTime.now();
    return entitlementMapper.revokeUnrevokedGrant(
            userId, definition.getId(), now, revokeReason, now)
        > 0;
  }

  private String normalizeEntitlementKey(String entitlementKey) {
    if (entitlementKey == null) {
      throw new ValidationException("entitlementKey is required");
    }
    String normalized = entitlementKey.trim();
    if (normalized.isEmpty()) {
      throw new ValidationException("entitlementKey is required");
    }
    return normalized;
  }

  private String normalizeDisplayName(String displayName, String entitlementKey) {
    if (displayName == null || displayName.isBlank()) {
      return entitlementKey;
    }
    return displayName.trim();
  }

  private EntitlementDefinition toEntitlementDefinitionDomain(EntitlementMapper.DefinitionRow row) {
    return new EntitlementDefinition(
        row.getId(),
        row.getEntitlementKey(),
        row.getDisplayName(),
        row.getDescription(),
        row.isEnabled(),
        row.getSysmeta(),
        row.getCreatedAt(),
        row.getUpdatedAt());
  }

  private EntitlementMapper.DefinitionRow toDefinitionRow(EntitlementDefinition definition) {
    EntitlementMapper.DefinitionRow row = new EntitlementMapper.DefinitionRow();
    row.setId(definition.getId());
    row.setEntitlementKey(definition.getEntitlementKey());
    row.setDisplayName(definition.getDisplayName());
    row.setDescription(definition.getDescription());
    row.setEnabled(definition.isEnabled());
    row.setSysmeta(definition.getSysmeta());
    row.setCreatedAt(definition.getCreatedAt());
    row.setUpdatedAt(definition.getUpdatedAt());
    return row;
  }

  private EntitlementMapper.GrantRow toGrantRow(UserEntitlementGrant grant) {
    EntitlementMapper.GrantRow row = new EntitlementMapper.GrantRow();
    row.setId(grant.getId());
    row.setUserId(grant.getUserId());
    row.setEntitlementId(grant.getEntitlementId());
    row.setGrantSource(grant.getGrantSource());
    row.setValidFrom(grant.getValidFrom());
    row.setExpiresAt(grant.getExpiresAt());
    row.setRevokedAt(grant.getRevokedAt());
    row.setGrantReason(grant.getGrantReason());
    row.setRevokeReason(grant.getRevokeReason());
    row.setSysmeta(grant.getSysmeta());
    row.setCreatedAt(grant.getCreatedAt());
    row.setUpdatedAt(grant.getUpdatedAt());
    return row;
  }

  private boolean isUniqueConstraintViolation(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current instanceof SQLException sqlException) {
        if ("23505".equals(sqlException.getSQLState())) {
          return true;
        }
      }
      current = current.getCause();
    }
    return false;
  }
}
