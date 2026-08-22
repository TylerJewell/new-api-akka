package io.akka.newapi.api;

import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.headers.RawHeader;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpClientProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.typesafe.config.Config;
import io.akka.newapi.application.DispatchSequence;
import io.akka.newapi.application.HttpUpstream;
import io.akka.newapi.domain.DispatchOutcome;
import io.akka.newapi.domain.RelayConfig;

/**
 * The gateway's own surface: the path a caller reaches. SPEC-001 §1, gui/manifest.json.
 *
 * <p>Request shape: {@code {"account_id", "group", "model", "prompt_tokens",
 * "max_output_tokens", ...rest forwarded to the upstream unchanged}}.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("")
public class RelayEndpoint extends AbstractHttpEndpoint {

  private final DispatchSequence dispatchSequence;

  public RelayEndpoint(ComponentClient componentClient, HttpClientProvider httpClientProvider, Config rawConfig) {
    var config = RelayConfig.of(rawConfig);
    this.dispatchSequence =
        new DispatchSequence(componentClient, new HttpUpstream(httpClientProvider.httpClientFor(config.upstreamService())), config);
  }

  @Post("/chat/completions")
  public HttpResponse completions(JsonNode requestBody) {
    String accountId = requestBody.path("account_id").asText("");
    String group = requestBody.path("group").asText("default");
    String model = requestBody.path("model").asText("");
    long promptTokens = requestBody.path("prompt_tokens").asLong(0);
    long declaredMaxOutputTokens = requestBody.path("max_output_tokens").asLong(0);

    DispatchOutcome outcome =
        dispatchSequence.dispatch(accountId, group, model, promptTokens, declaredMaxOutputTokens, requestBody.toString());

    return HttpResponse.create()
        .withStatus(outcome.status())
        .addHeaders(
            java.util.List.of(
                RawHeader.create("X-Attempts", Integer.toString(outcome.attempts())),
                RawHeader.create("X-Stopped-Because", outcome.stoppedBecause().name()),
                RawHeader.create("X-Preconsumed-Quota", Long.toString(outcome.preConsumedQuota())),
                RawHeader.create("X-Settled-Quota", Long.toString(outcome.settledQuota()))))
        .withEntity(
            akka.http.javadsl.model.ContentTypes.APPLICATION_JSON,
            outcome.body().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
