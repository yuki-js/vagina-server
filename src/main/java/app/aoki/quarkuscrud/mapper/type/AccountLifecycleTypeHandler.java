package app.aoki.quarkuscrud.mapper.type;

import app.aoki.quarkuscrud.entity.AccountLifecycle;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * Custom MyBatis type handler that maps the AccountLifecycle enum to the database string
 * representation and vice versa. Handles both enum names (e.g., CREATED) and custom values (e.g.,
 * created) to tolerate historical data casing.
 */
public class AccountLifecycleTypeHandler extends BaseTypeHandler<AccountLifecycle> {

  @Override
  public void setNonNullParameter(
      PreparedStatement ps, int i, AccountLifecycle parameter, JdbcType jdbcType)
      throws SQLException {
    ps.setString(i, parameter.getValue());
  }

  @Override
  public AccountLifecycle getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return toAccountLifecycle(rs.getString(columnName));
  }

  @Override
  public AccountLifecycle getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return toAccountLifecycle(rs.getString(columnIndex));
  }

  @Override
  public AccountLifecycle getNullableResult(CallableStatement cs, int columnIndex)
      throws SQLException {
    return toAccountLifecycle(cs.getString(columnIndex));
  }

  private AccountLifecycle toAccountLifecycle(String value) throws SQLException {
    if (value == null) {
      return null;
    }

    String normalized = value.trim();
    if (normalized.isEmpty()) {
      return null;
    }

    try {
      return AccountLifecycle.valueOf(normalized.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      for (AccountLifecycle lifecycle : AccountLifecycle.values()) {
        if (lifecycle.getValue().equalsIgnoreCase(normalized)) {
          return lifecycle;
        }
      }
      throw new SQLException("Unknown account lifecycle value: " + value, ex);
    }
  }
}
