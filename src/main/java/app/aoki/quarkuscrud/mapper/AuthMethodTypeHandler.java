package app.aoki.quarkuscrud.mapper;

import app.aoki.quarkuscrud.entity.AuthMethod;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * MyBatis TypeHandler for AuthMethod enum that uses the string value instead of enum name.
 *
 * <p>This handler ensures that AuthMethod enums are stored as their lowercase string values (e.g.,
 * "anonymous", "oidc") in the database, rather than their uppercase enum names.
 */
@MappedTypes(AuthMethod.class)
public class AuthMethodTypeHandler extends BaseTypeHandler<AuthMethod> {

  @Override
  public void setNonNullParameter(
      PreparedStatement ps, int i, AuthMethod parameter, JdbcType jdbcType) throws SQLException {
    ps.setString(i, parameter.getValue());
  }

  @Override
  public AuthMethod getNullableResult(ResultSet rs, String columnName) throws SQLException {
    String value = rs.getString(columnName);
    return value == null ? null : AuthMethod.fromValue(value);
  }

  @Override
  public AuthMethod getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    String value = rs.getString(columnIndex);
    return value == null ? null : AuthMethod.fromValue(value);
  }

  @Override
  public AuthMethod getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    String value = cs.getString(columnIndex);
    return value == null ? null : AuthMethod.fromValue(value);
  }
}
