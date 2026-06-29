package app.vagina.server.mapper.type;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

@MappedTypes(UUID.class)
public class UuidTypeHandler extends BaseTypeHandler<UUID> {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, UUID parameter, JdbcType jdbcType)
      throws SQLException {
    ps.setObject(i, parameter);
  }

  @Override
  public UUID getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return toUuid(rs.getObject(columnName));
  }

  @Override
  public UUID getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return toUuid(rs.getObject(columnIndex));
  }

  @Override
  public UUID getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return toUuid(cs.getObject(columnIndex));
  }

  private UUID toUuid(Object value) throws SQLException {
    if (value == null) {
      return null;
    }
    if (value instanceof UUID uuid) {
      return uuid;
    }
    if (value instanceof String text) {
      return UUID.fromString(text);
    }
    throw new SQLException("Unsupported UUID value type: " + value.getClass().getName());
  }
}
