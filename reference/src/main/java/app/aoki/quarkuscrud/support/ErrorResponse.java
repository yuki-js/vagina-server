package app.aoki.quarkuscrud.support;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Standard error response format for the API.
 *
 * @param error the error message
 */
@RegisterForReflection
public record ErrorResponse(String error) {}
