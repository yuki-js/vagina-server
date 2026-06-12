package app.vagina.server.mapper.type;

import app.vagina.server.entity.AuthMethod;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

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
