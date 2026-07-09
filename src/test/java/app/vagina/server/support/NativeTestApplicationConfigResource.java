package app.vagina.server.support;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.nio.file.Path;
import java.util.Map;

/** Makes JVM test application config visible to the launched native executable. */
public class NativeTestApplicationConfigResource implements QuarkusTestResourceLifecycleManager {

  @Override
  public Map<String, String> start() {
    Path testApplicationYaml = Path.of("src/test/resources/application.yaml").toAbsolutePath();
    return Map.of("quarkus.config.locations", testApplicationYaml.toUri().toString());
  }

  @Override
  public void stop() {}
}
