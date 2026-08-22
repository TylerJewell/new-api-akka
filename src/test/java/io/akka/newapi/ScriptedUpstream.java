package io.akka.newapi;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A provider that answers from a script instead of a model. The network and the model are
 * the two things the port is allowed to stand in for -- a timing that includes a real
 * model measures that model, not the rebuild.
 *
 * <p>Every call is recorded by the channel id header it carried, so a retry sequence is
 * counted by what the upstream actually saw rather than by what the port claims it did.
 */
public final class ScriptedUpstream {

  private final AtomicReference<List<Integer>> statuses = new AtomicReference<>(List.of(200));
  private final CopyOnWriteArrayList<String> channelsSeen = new CopyOnWriteArrayList<>();
  private final AtomicLong promptTokensPerAnswer = new AtomicLong(0);
  private final AtomicLong completionTokensPerAnswer = new AtomicLong(0);

  public HttpResponse answer(HttpRequest request) {
    channelsSeen.add(request.getHeader("X-Channel-Id").map(h -> h.value()).orElse("(none)"));
    var script = statuses.get();
    int status = script.get(Math.min(channelsSeen.size() - 1, script.size() - 1));
    return HttpResponse.create()
        .withStatus(StatusCodes.get(status))
        .withEntity(ContentTypes.APPLICATION_JSON, body(status).getBytes(StandardCharsets.UTF_8));
  }

  private String body(int status) {
    if (status < 200 || status >= 300) {
      return "{\"status\":" + status + "}";
    }
    return "{\"status\":"
        + status
        + ",\"usage\":{\"prompt_tokens\":"
        + promptTokensPerAnswer.get()
        + ",\"completion_tokens\":"
        + completionTokensPerAnswer.get()
        + "}}";
  }

  public void reset() {
    channelsSeen.clear();
    statuses.set(List.of(200));
    promptTokensPerAnswer.set(0);
    completionTokensPerAnswer.set(0);
  }

  public void answersWith(Integer... script) {
    statuses.set(List.of(script));
  }

  public void reportsTokens(long prompt, long completion) {
    promptTokensPerAnswer.set(prompt);
    completionTokensPerAnswer.set(completion);
  }

  public List<String> channelsSeen() {
    return List.copyOf(channelsSeen);
  }
}
