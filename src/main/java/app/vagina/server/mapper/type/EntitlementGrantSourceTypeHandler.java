package app.vagina.server.mapper.type;

import app.vagina.server.entity.EntitlementGrantSource;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

public class EntitlementGrantSourceTypeHandler extends BaseTypeHandler<EntitlementGrantSource> {

  @Override
  public void setNonNullParameter(
      PreparedStatement ps, int i, EntitlementGrantSource parameter, JdbcType jdbcType)
      throws SQLException {
    ps.setString(i, parameter.getValue());
  }

  @Override
  public EntitlementGrantSource getNullableResult(ResultSet rs, String columnName)
      throws SQLException {
    return toEntitlementGrantSource(rs.getString(columnName));
  }

  @Override
  public EntitlementGrantSource getNullableResult(ResultSet rs, int columnIndex)
      throws SQLException {
    return toEntitlementGrantSource(rs.getString(columnIndex));
  }

  @Override
  public EntitlementGrantSource getNullableResult(CallableStatement cs, int columnIndex)
      throws SQLException {
    return toEntitlementGrantSource(cs.getString(columnIndex));
  }

  private EntitlementGrantSource toEntitlementGrantSource(String value) throws SQLException {
    if (value == null) {
      return null;
    }

    String normalized = value.trim();
    if (normalized.isEmpty()) {
      return null;
    }

    try {
      return EntitlementGrantSource.valueOf(normalized.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      try {
        return EntitlementGrantSource.fromValue(normalized);
      } catch (IllegalArgumentException ignored) {
        throw new SQLException("Unknown entitlement grant source value: " + value, ex);
      }
    }
  }
}
