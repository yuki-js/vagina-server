package app.vagina.server.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

@ConfigMapping(prefix = "vagina.object-storage")
public interface ObjectStorageConfig {
  @WithDefault("")
  String endpoint();

  @WithDefault("")
  String bucket();

  @WithDefault("us-east-1")
  String region();

  @WithDefault("")
  String accessKey();

  @WithDefault("")
  String secretKey();

  Optional<String> pathPrefix();
}
