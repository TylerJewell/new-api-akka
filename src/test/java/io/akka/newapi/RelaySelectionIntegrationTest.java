package io.akka.newapi;

import static org.assertj.core.api.Assertions.assertThat;

import akka.http.javadsl.model.ContentTypes;
import akka.javasdk.http.StrictResponse;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.newapi.application.AbilityIndexEntity;
import io.akka.newapi.application.AccountEntity;
import io.akka.newapi.application.ChannelEntity;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §3 rules 1-5 with the runtime running: channel selection driven through the
 * gateway's own HTTP surface, the way an external caller reaches it.
 */
public class RelaySelectionIntegrationTest extends TestKitSupport {

  private static final ScriptedUpstream UPSTREAM = new ScriptedUpstream();

  @BeforeEach
  public void resetTheUpstream() {
    UPSTREAM.reset();
    UPSTREAM.answersWith(200);
  }

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withAdditionalConfig(
            """
            new-api.dispatch.max-retries = 2
            new-api.pricing.min-pre-consume-tokens = 0
            new-api.auto-disable.enabled = false
            """)
        .withMockedHttpService("upstream", UPSTREAM::answer);
  }

  private void registerChannel(long id, String group, String model, long priority, int weight) {
    componentClient
        .forKeyValueEntity(Long.toString(id))
        .method(ChannelEntity::register)
        .invoke(new ChannelEntity.Register(Set.of(group), Set.of(model), priority, weight, true));
    componentClient
        .forKeyValueEntity(group + "::" + model)
        .method(AbilityIndexEntity::addChannel)
        .invoke(new AbilityIndexEntity.ChannelId(id));
  }

  private void fund(String accountId, long amount) {
    componentClient.forKeyValueEntity(accountId).method(AccountEntity::deposit).invoke(new AccountEntity.Deposit(amount));
  }

  private StrictResponse<String> completions(String group, String model, String accountId) {
    fund(accountId, 10_000);
    var body =
        "{\"account_id\":\""
            + accountId
            + "\",\"group\":\""
            + group
            + "\",\"model\":\""
            + model
            + "\",\"prompt_tokens\":10}";
    return httpClient
        .POST("/chat/completions")
        .withRequestBody(ContentTypes.APPLICATION_JSON, body.getBytes(StandardCharsets.UTF_8))
        .parseResponseBody(b -> new String(b, StandardCharsets.UTF_8))
        .invoke();
  }

  @Test
  public void bothChannelsInTheTopTierAreReachableOverManyRequests() {
    // question-log #1: a weight-0 channel is still reachable, via the +10 baseline.
    registerChannel(101, "g1", "gpt-4", 100, 0);
    registerChannel(102, "g1", "gpt-4", 100, 0);
    var seen = new HashMap<String, Integer>();
    for (int i = 0; i < 60; i++) {
      completions("g1", "gpt-4", "acct-selection-1");
      var channelsUsed = UPSTREAM.channelsSeen();
      seen.merge(channelsUsed.get(channelsUsed.size() - 1), 1, Integer::sum);
    }
    assertThat(seen.keySet()).containsExactlyInAnyOrder("101", "102");
  }

  @Test
  public void theHigherPriorityTierIsPreferredOnTheFirstAttempt() {
    registerChannel(201, "g2", "gpt-4", 100, 0); // top tier
    registerChannel(202, "g2", "gpt-4", 50, 0); // lower tier
    UPSTREAM.answersWith(200);
    completions("g2", "gpt-4", "acct-selection-2");
    assertThat(UPSTREAM.channelsSeen()).containsExactly("201");
  }

  @Test
  public void aFailureOnTheTopTierFallsBackToTheNextTierAndSucceeds() {
    registerChannel(301, "g3", "gpt-4", 100, 0);
    registerChannel(302, "g3", "gpt-4", 50, 0);
    UPSTREAM.reportsTokens(10, 5);
    UPSTREAM.answersWith(500, 200);
    var response = completions("g3", "gpt-4", "acct-selection-3");
    assertThat(UPSTREAM.channelsSeen()).containsExactly("301", "302");
    assertThat(response.status().intValue()).isEqualTo(200);
    assertThat(header(response, "X-Stopped-Because")).isEqualTo("SUCCESS");
  }

  @Test
  public void noChannelRegisteredForThePairReturnsNoChannelAvailable() {
    var response = completions("nowhere", "nothing", "acct-selection-4");
    assertThat(header(response, "X-Stopped-Because")).isEqualTo("NO_CHANNEL_AVAILABLE");
  }

  private static String header(StrictResponse<String> response, String name) {
    return response.httpResponse().getHeader(name).map(h -> h.value()).orElse("(absent)");
  }
}
