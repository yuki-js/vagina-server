package app.vagina.server.support;

import java.time.Duration;

public final class Constants {
  private Constants() {}

  public static final int VFS_MAX_PATH_LENGTH = 512;
  public static final int VFS_MAX_RAW_PATH_LENGTH = 8192;
  public static final String VFS_RESERVED_SYSTEM_PATH = "/system";

  public static final Duration SERVER_COMMON_HTTP_TIMEOUT = Duration.ofSeconds(10);
  public static final Duration TEXT_AGENT_HTTP_REQUEST_TIMEOUT = Duration.ofMinutes(30);
  public static final int AI_PROVIDER_MAX_RESPONSE_BYTES = 10 * 1024 * 1024;
  public static final String NO_AUTH_API_KEY = "__NO_AUTH__";

  public static final String VHRP_RESUME_RETENTION_CONFIG_DEFAULT = "PT15S";
  public static final Duration VHRP_RESUME_RETENTION = Duration.ofSeconds(15);

  public static final int TEXT_AGENT_MAX_IMAGE_COUNT = 4;
  public static final int TEXT_AGENT_MAX_IMAGE_BYTES = 8 * 1024 * 1024;
}
