package buildutil;

import io.swagger.v3.core.util.Yaml;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Bundles modular OpenAPI source files into a single specification file.
 *
 * <p>This utility is executed as part of the build via Gradle and does not depend on Quarkus
 * runtime components.
 */
public final class OpenApiCompiler {

  private OpenApiCompiler() {}

  public static void main(String[] args) {
    if (args.length != 2) {
      System.err.println("Usage: OpenApiCompiler <input-openapi-root> <output-openapi-yaml>");
      System.exit(1);
    }

    Path input = Paths.get(args[0]);
    Path output = Paths.get(args[1]);

    try {
      compile(input, output);
    } catch (Exception ex) {
      System.err.println("Failed to compile OpenAPI specification:");
      ex.printStackTrace(System.err);
      System.exit(1);
    }
  }

  private static void compile(Path input, Path output) throws IOException {
    if (!Files.exists(input)) {
      throw new IOException("Input OpenAPI file not found: " + input);
    }

    ParseOptions options = new ParseOptions();
    options.setResolve(true);

    SwaggerParseResult parseResult =
        new OpenAPIV3Parser().readLocation(input.toUri().toString(), null, options);

    List<String> messages = parseResult.getMessages();
    if (messages != null && !messages.isEmpty()) {
      messages.forEach(message -> System.err.println("OpenAPI parse message: " + message));
      if (parseResult.getOpenAPI() == null) {
        throw new IOException(
            "Failed to parse OpenAPI specification: " + String.join(", ", messages));
      }
    }

    OpenAPI openAPI = parseResult.getOpenAPI();
    if (openAPI == null) {
      throw new IOException("OpenAPI parse result did not contain a specification.");
    }

    String yaml = Yaml.mapper().writeValueAsString(openAPI);

    Path parent = output.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }

    Files.writeString(output, yaml);
  }
}
