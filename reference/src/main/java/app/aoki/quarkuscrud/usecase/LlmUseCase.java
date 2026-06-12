package app.aoki.quarkuscrud.usecase;

import app.aoki.quarkuscrud.generated.model.FakeNamesRequest;
import app.aoki.quarkuscrud.generated.model.FakeNamesResponse;
import app.aoki.quarkuscrud.service.LlmService;
import app.aoki.quarkuscrud.service.RateLimiterService;
import app.aoki.quarkuscrud.service.SimilarityLevel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Use case for LLM-related business flows.
 *
 * <p>This use case orchestrates LLM operations including rate limiting, validation, and response
 * mapping.
 */
@ApplicationScoped
public class LlmUseCase {

  @Inject LlmService llmService;
  @Inject RateLimiterService rateLimiterService;

  /**
   * Generates fake names similar to the input name.
   *
   * @param userId the user ID for rate limiting
   * @param request the fake names generation request
   * @return the response containing generated names
   * @throws RateLimitExceededException if rate limit is exceeded
   * @throws IllegalArgumentException if request validation fails
   */
  public FakeNamesResponse generateFakeNames(Long userId, FakeNamesRequest request) {
    // Check rate limit
    if (!rateLimiterService.allowRequest(userId)) {
      throw new RateLimitExceededException(
          "Rate limit exceeded. Please try again later. (Limit: 100 requests per minute per user,"
              + " 300 requests per minute globally)");
    }

    // Validate request
    if (request.getInputName() == null || request.getInputName().isBlank()) {
      throw new IllegalArgumentException("Input name is required");
    }

    if (request.getVariance() == null) {
      throw new IllegalArgumentException("Similarity level is required");
    }

    // Convert variance enum to SimilarityLevel enum
    SimilarityLevel level;
    try {
      level = SimilarityLevel.fromValue(request.getVariance().value());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid similarity level: " + request.getVariance(), e);
    }

    // Check for prompt injection in customPrompt
    try {
      llmService.checkPromptInjection(request.getCustomPrompt());
    } catch (SecurityException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }

    // Generate fake names through service
    List<String> fakeNames =
        llmService.generateFakeNames(request.getInputName(), level, request.getCustomPrompt());

    // Map to response DTO
    FakeNamesResponse response = new FakeNamesResponse();
    response.setOutput(new LinkedHashSet<>(fakeNames));

    return response;
  }

  /** Exception thrown when rate limit is exceeded. */
  public static class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String message) {
      super(message);
    }
  }
}
