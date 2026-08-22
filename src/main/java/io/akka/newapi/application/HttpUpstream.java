package io.akka.newapi.application;

import akka.http.javadsl.model.ContentTypes;
import akka.javasdk.http.HttpClient;
import akka.javasdk.http.StrictResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;

/**
 * Dispatches over HTTP to whichever channel was selected. The channel id is carried as a
 * header rather than resolved to a distinct base URL per channel -- this port speaks one
 * upstream dialect and treats the provider as opaque (SPEC-001 §1), the same boundary
 * higress-port drew for the identical reason.
 *
 * <p>A connection failure (no HTTP response at all) is the one case this port classifies
 * as a channel-side error (SPEC-001 §3 rule 7, 11) -- there is definitionally no "answer"
 * from the specific upstream call to blame instead.
 */
public class HttpUpstream implements Upstream {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final HttpClient client;

  public HttpUpstream(HttpClient client) {
    this.client = client;
  }

  @Override
  public Answer call(long channelId, String body) {
    try {
      StrictResponse<String> response =
          client
              .POST("/chat/completions")
              .addHeader("X-Channel-Id", Long.toString(channelId))
              .withRequestBody(ContentTypes.APPLICATION_JSON, body.getBytes(StandardCharsets.UTF_8))
              .parseResponseBody(bytes -> new String(bytes, StandardCharsets.UTF_8))
              .invoke();
      return readAnswer(response.status().intValue(), response.body());
    } catch (Exception e) {
      return new Answer(503, "{\"error\":\"" + e.getMessage() + "\"}", 0, 0, true);
    }
  }

  private static Answer readAnswer(int status, String body) {
    try {
      var usage = MAPPER.readTree(body).path("usage");
      long prompt = usage.path("prompt_tokens").asLong(0);
      long completion = usage.path("completion_tokens").asLong(0);
      return new Answer(status, body, prompt, completion, false);
    } catch (Exception e) {
      return new Answer(status, body, 0, 0, false);
    }
  }
}
