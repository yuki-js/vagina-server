package app.vagina.server.service;

import app.vagina.server.mapper.AuthenticationEventMapper;
import app.vagina.server.support.AuthRequestMetadata;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDateTime;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AuthenticationEventRecorder {
  private static final Logger LOG = Logger.getLogger(AuthenticationEventRecorder.class);

  @Inject AuthenticationEventMapper authenticationEventMapper;

  public void record(
      Long userId, String tokenFamily, String eventType, AuthRequestMetadata requestMetadata) {
    try {
      QuarkusTransaction.requiringNew()
          .run(
              () ->
                  authenticationEventMapper.insert(
                      userId,
                      tokenFamily,
                      eventType,
                      requestMetadata.ipAddress(),
                      requestMetadata.userAgent(),
                      LocalDateTime.now()));
    } catch (RuntimeException exception) {
      LOG.warnf(
          exception,
          "Failed to record successful authentication event userId=%s tokenFamily=%s eventType=%s",
          userId,
          tokenFamily,
          eventType);
    }
  }
}
