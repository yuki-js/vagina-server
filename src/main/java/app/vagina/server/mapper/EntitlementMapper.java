package app.vagina.server.mapper;

import app.vagina.server.entity.EntitlementGrantSource;
import app.vagina.server.mapper.type.EntitlementGrantSourceTypeHandler;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface EntitlementMapper {

  @Insert(
      "INSERT INTO entitlement_definitions "
          + "(entitlement_key, display_name, description, enabled, sysmeta, created_at, updated_at) "
          + "VALUES (#{entitlementKey}, #{displayName}, #{description}, #{enabled}, "
          + "#{sysmeta}::jsonb, #{createdAt}, #{updatedAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insertDefinition(DefinitionRow row);

  @Select(
      "SELECT id, entitlement_key, display_name, description, enabled, sysmeta::text as sysmeta, "
          + "created_at, updated_at FROM entitlement_definitions WHERE entitlement_key = #{key}")
  @Results(
      id = "entitlementDefinitionResultMap",
      value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "entitlementKey", column = "entitlement_key"),
        @Result(property = "displayName", column = "display_name"),
        @Result(property = "description", column = "description"),
        @Result(property = "enabled", column = "enabled"),
        @Result(property = "sysmeta", column = "sysmeta"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
      })
  Optional<DefinitionRow> findDefinitionByKey(@Param("key") String key);

  @Select(
      "SELECT id, entitlement_key, display_name, description, enabled, sysmeta::text as sysmeta, "
          + "created_at, updated_at FROM entitlement_definitions WHERE id = #{id}")
  @Results(
      id = "entitlementDefinitionByIdResultMap",
      value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "entitlementKey", column = "entitlement_key"),
        @Result(property = "displayName", column = "display_name"),
        @Result(property = "description", column = "description"),
        @Result(property = "enabled", column = "enabled"),
        @Result(property = "sysmeta", column = "sysmeta"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
      })
  Optional<DefinitionRow> findDefinitionById(@Param("id") Long id);

  @Insert(
      "INSERT INTO user_entitlement_grants "
          + "(user_id, entitlement_id, grant_source, valid_from, expires_at, revoked_at, "
          + "grant_reason, revoke_reason, sysmeta, created_at, updated_at) "
          + "VALUES (#{userId}, #{entitlementId}, "
          + "#{grantSource, typeHandler=app.vagina.server.mapper.type.EntitlementGrantSourceTypeHandler}, "
          + "#{validFrom}, #{expiresAt}, #{revokedAt}, #{grantReason}, #{revokeReason}, "
          + "#{sysmeta}::jsonb, #{createdAt}, #{updatedAt})")
  @Options(useGeneratedKeys = true, keyProperty = "id")
  void insertGrant(GrantRow row);

  @Select(
      "SELECT id, user_id, entitlement_id, grant_source, valid_from, expires_at, revoked_at, "
          + "grant_reason, revoke_reason, sysmeta::text as sysmeta, created_at, updated_at "
          + "FROM user_entitlement_grants WHERE id = #{id}")
  @Results(
      id = "entitlementGrantResultMap",
      value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "entitlementId", column = "entitlement_id"),
        @Result(
            property = "grantSource",
            column = "grant_source",
            javaType = EntitlementGrantSource.class,
            typeHandler = EntitlementGrantSourceTypeHandler.class),
        @Result(property = "validFrom", column = "valid_from"),
        @Result(property = "expiresAt", column = "expires_at"),
        @Result(property = "revokedAt", column = "revoked_at"),
        @Result(property = "grantReason", column = "grant_reason"),
        @Result(property = "revokeReason", column = "revoke_reason"),
        @Result(property = "sysmeta", column = "sysmeta"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
      })
  Optional<GrantRow> findGrantById(@Param("id") Long id);

  @Select(
      "SELECT g.id, g.user_id, g.entitlement_id, g.grant_source, g.valid_from, g.expires_at, "
          + "g.revoked_at, g.grant_reason, g.revoke_reason, g.sysmeta::text as sysmeta, "
          + "g.created_at, g.updated_at "
          + "FROM user_entitlement_grants g "
          + "JOIN entitlement_definitions d ON d.id = g.entitlement_id "
          + "WHERE g.user_id = #{userId} AND d.entitlement_key = #{entitlementKey} "
          + "AND g.revoked_at IS NULL "
          + "ORDER BY g.created_at DESC LIMIT 1")
  @Results(
      id = "entitlementGrantByUserKeyResultMap",
      value = {
        @Result(property = "id", column = "id", id = true),
        @Result(property = "userId", column = "user_id"),
        @Result(property = "entitlementId", column = "entitlement_id"),
        @Result(
            property = "grantSource",
            column = "grant_source",
            javaType = EntitlementGrantSource.class,
            typeHandler = EntitlementGrantSourceTypeHandler.class),
        @Result(property = "validFrom", column = "valid_from"),
        @Result(property = "expiresAt", column = "expires_at"),
        @Result(property = "revokedAt", column = "revoked_at"),
        @Result(property = "grantReason", column = "grant_reason"),
        @Result(property = "revokeReason", column = "revoke_reason"),
        @Result(property = "sysmeta", column = "sysmeta"),
        @Result(property = "createdAt", column = "created_at"),
        @Result(property = "updatedAt", column = "updated_at")
      })
  Optional<GrantRow> findUnrevokedGrantByUserAndKey(
      @Param("userId") Long userId, @Param("entitlementKey") String entitlementKey);

  @Select(
      "SELECT DISTINCT d.entitlement_key "
          + "FROM user_entitlement_grants g "
          + "JOIN entitlement_definitions d ON d.id = g.entitlement_id "
          + "WHERE g.user_id = #{userId} AND d.enabled = TRUE "
          + "AND g.revoked_at IS NULL "
          + "AND g.valid_from <= #{now} "
          + "AND (g.expires_at IS NULL OR g.expires_at > #{now}) "
          + "ORDER BY d.entitlement_key")
  List<String> findActiveEntitlementKeys(@Param("userId") Long userId, @Param("now") LocalDateTime now);

  @Update(
      "UPDATE user_entitlement_grants SET revoked_at = #{revokedAt}, revoke_reason = #{revokeReason}, "
          + "updated_at = #{updatedAt} "
          + "WHERE user_id = #{userId} AND entitlement_id = #{entitlementId} AND revoked_at IS NULL")
  int revokeUnrevokedGrant(
      @Param("userId") Long userId,
      @Param("entitlementId") Long entitlementId,
      @Param("revokedAt") LocalDateTime revokedAt,
      @Param("revokeReason") String revokeReason,
      @Param("updatedAt") LocalDateTime updatedAt);

  final class DefinitionRow {
    private Long id;
    private String entitlementKey;
    private String displayName;
    private String description;
    private boolean enabled;
    private String sysmeta;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
      return id;
    }

    public void setId(Long id) {
      this.id = id;
    }

    public String getEntitlementKey() {
      return entitlementKey;
    }

    public void setEntitlementKey(String entitlementKey) {
      this.entitlementKey = entitlementKey;
    }

    public String getDisplayName() {
      return displayName;
    }

    public void setDisplayName(String displayName) {
      this.displayName = displayName;
    }

    public String getDescription() {
      return description;
    }

    public void setDescription(String description) {
      this.description = description;
    }

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getSysmeta() {
      return sysmeta;
    }

    public void setSysmeta(String sysmeta) {
      this.sysmeta = sysmeta;
    }

    public LocalDateTime getCreatedAt() {
      return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
      this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
      return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
      this.updatedAt = updatedAt;
    }
  }

  final class GrantRow {
    private Long id;
    private Long userId;
    private Long entitlementId;
    private EntitlementGrantSource grantSource;
    private LocalDateTime validFrom;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private String grantReason;
    private String revokeReason;
    private String sysmeta;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
      return id;
    }

    public void setId(Long id) {
      this.id = id;
    }

    public Long getUserId() {
      return userId;
    }

    public void setUserId(Long userId) {
      this.userId = userId;
    }

    public Long getEntitlementId() {
      return entitlementId;
    }

    public void setEntitlementId(Long entitlementId) {
      this.entitlementId = entitlementId;
    }

    public EntitlementGrantSource getGrantSource() {
      return grantSource;
    }

    public void setGrantSource(EntitlementGrantSource grantSource) {
      this.grantSource = grantSource;
    }

    public LocalDateTime getValidFrom() {
      return validFrom;
    }

    public void setValidFrom(LocalDateTime validFrom) {
      this.validFrom = validFrom;
    }

    public LocalDateTime getExpiresAt() {
      return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
      this.expiresAt = expiresAt;
    }

    public LocalDateTime getRevokedAt() {
      return revokedAt;
    }

    public void setRevokedAt(LocalDateTime revokedAt) {
      this.revokedAt = revokedAt;
    }

    public String getGrantReason() {
      return grantReason;
    }

    public void setGrantReason(String grantReason) {
      this.grantReason = grantReason;
    }

    public String getRevokeReason() {
      return revokeReason;
    }

    public void setRevokeReason(String revokeReason) {
      this.revokeReason = revokeReason;
    }

    public String getSysmeta() {
      return sysmeta;
    }

    public void setSysmeta(String sysmeta) {
      this.sysmeta = sysmeta;
    }

    public LocalDateTime getCreatedAt() {
      return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
      this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
      return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
      this.updatedAt = updatedAt;
    }
  }
}
