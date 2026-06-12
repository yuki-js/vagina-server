package app.vagina.server.mapper.type;

import app.vagina.server.entity.ClientType;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

@MappedTypes(ClientType.class)
public class ClientTypeHandler extends BaseTypeHandler<ClientType> {

  @Override
  public void setNonNullParameter(
      PreparedStatement ps, int i, ClientType parameter, JdbcType jdbcType) throws SQLException {
    ps.setString(i, parameter.getValue());
  }

  @Override
  public ClientType getNullableResult(ResultSet rs, String columnName) throws SQLException {
    String value = rs.getString(columnName);
    return value == null ? null : ClientType.fromValue(value);
  }

  @Override
  public ClientType getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    String value = rs.getString(columnIndex);
    return value == null ? null : ClientType.fromValue(value);
  }

  @Override
  public ClientType getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    String value = cs.getString(columnIndex);
    return value == null ? null : ClientType.fromValue(value);
  }
}
