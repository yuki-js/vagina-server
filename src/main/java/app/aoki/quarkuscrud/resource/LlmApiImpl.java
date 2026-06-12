package app.aoki.quarkuscrud.resource;

import app.aoki.quarkuscrud.entity.User;
import app.aoki.quarkuscrud.generated.api.LlmApi;
import app.aoki.quarkuscrud.generated.model.FakeNamesRequest;
import app.aoki.quarkuscrud.generated.model.FakeNamesResponse;
import app.aoki.quarkuscrud.support.Authenticated;
import app.aoki.quarkuscrud.support.AuthenticatedUser;
import app.aoki.quarkuscrud.support.ErrorResponse;
import app.aoki.quarkuscrud.usecase.LlmUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

@ApplicationScoped
@Path("/api/llm/fake-names")
public class LlmApiImpl implements LlmApi {

  private static final Logger LOG = Logger.getLogger(LlmApiImpl.class);

  @Inject LlmUseCase llmUseCase;

  @Inject AuthenticatedUser authenticatedUser;

  @Inject MeterRegistry meterRegistry;

  @Override
  @Authenticated
  public Response generateFakeNames(FakeNamesRequest request) {
    User user = authenticatedUser.get();
    LOG.infof(
        "Request received: generate fake names for user %d (input: %s, variance: %s,"
            + " customPrompt: %s)",
        user.getId(),
        request.getInputName(),
        request.getVariance() != null ? request.getVariance().value() : "null",
        request.getCustomPrompt() != null ? request.getCustomPrompt() : "null");

    Timer.Sample sample = Timer.start(meterRegistry);

    try {
      FakeNamesResponse response = llmUseCase.generateFakeNames(user.getId(), request);

      meterRegistry.counter("api.llm.fake_names.success").increment();
      LOG.infof(
          "Successfully generated %d fake names for user %d",
          response.getOutput().size(), user.getId());

      return Response.ok(response).build();

    } catch (LlmUseCase.RateLimitExceededException e) {
      LOG.warnf("Rate limit exceeded for user %d", user.getId());
      meterRegistry.counter("api.llm.rate_limit_exceeded").increment();
      return Response.status(Response.Status.TOO_MANY_REQUESTS)
          .entity(new ErrorResponse(e.getMessage()))
          .build();

    } catch (IllegalArgumentException e) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity(new ErrorResponse(e.getMessage()))
          .build();

    } catch (Exception e) {
      LOG.errorf(e, "Failed to generate fake names for user %d", user.getId());
      meterRegistry.counter("api.llm.fake_names.error").increment();
      return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
          .entity(new ErrorResponse("Failed to generate fake names: " + e.getMessage()))
          .build();
    } finally {
      sample.stop(meterRegistry.timer("api.llm.fake_names.duration"));
    }
  }
}
