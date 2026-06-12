package app.aoki.quarkuscrud.support;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

/** Health check for database connectivity. */
@Liveness
@ApplicationScoped
public class DatabaseHealthCheck implements HealthCheck {

  @Inject AgroalDataSource dataSource;

  @Override
  public HealthCheckResponse call() {
    try (Connection connection = dataSource.getConnection()) {
      boolean isValid = connection.isValid(2);
      return HealthCheckResponse.named("Database connection health check")
          .status(isValid)
          .withData("database", "PostgreSQL")
          .build();
    } catch (SQLException e) {
      return HealthCheckResponse.named("Database connection health check")
          .down()
          .withData("error", e.getMessage())
          .build();
    }
  }
}
