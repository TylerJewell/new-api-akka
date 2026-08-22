package io.akka.newapi;

import static org.assertj.core.api.Assertions.assertThat;

import akka.http.javadsl.model.ContentTypes;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.newapi.application.AbilityIndexEntity;
import io.akka.newapi.application.AccountEntity;
import io.akka.newapi.application.ChannelEntity;
import io.akka.newapi.domain.ChannelStatus;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §3 rules 10-12 with the runtime running: one qualifying failure disables a
 * channel immediately, gated by two independent flags -- never a consecutive-failure
 * count (question-log #6, #7, #8).
 */
public class AutoDisableIntegrationTest extends TestKitSupport {

  private static final ScriptedUpstream UPSTREAM = new ScriptedUpstream();

  @BeforeEach
  public void resetTheUpstream() {
    UPSTREAM.reset();
  }

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withAdditionalConfig(
            """
            new-api.dispatch.max-retries = 0
            new-api.pricing.min-pre-consume-tokens = 0
            new-api.auto-disable.enabled = true
            new-api.auto-disable.disable-on-status = [401]
            """)
        .withMockedHttpService("upstream", UPSTREAM::answer);
  }

  private void registerChannel(long id, String group, String model, boolean autoBan) {
    componentClient
        .forKeyValueEntity(Long.toString(id))
        .method(ChannelEntity::register)
        .invoke(new ChannelEntity.Register(Set.of(group), Set.of(model), 100, 0, autoBan));
    componentClient
        .forKeyValueEntity(group + "::" + model)
        .method(AbilityIndexEntity::addChannel)
        .invoke(new AbilityIndexEntity.ChannelId(id));
  }

  private ChannelStatus channelStatus(long id) {
    return componentClient.forKeyValueEntity(Long.toString(id)).method(ChannelEntity::get).invoke().status();
  }

  private void completions(String group, String model, String accountId) {
    componentClient.forKeyValueEntity(accountId).method(AccountEntity::deposit).invoke(new AccountEntity.Deposit(10_000));
    var body = "{\"account_id\":\"" + accountId + "\",\"group\":\"" + group + "\",\"model\":\"" + model + "\",\"prompt_tokens\":10}";
    httpClient
        .POST("/chat/completions")
        .withRequestBody(ContentTypes.APPLICATION_JSON, body.getBytes(StandardCharsets.UTF_8))
        .parseResponseBody(b -> new String(b, StandardCharsets.UTF_8))
        .invoke();
  }

  @Test
  public void aSingleQualifyingFailureDisablesTheChannelImmediately() {
    registerChannel(501, "disable-1", "gpt-4", true);
    UPSTREAM.answersWith(401);
    completions("disable-1", "gpt-4", "acct-disable-1");
    // one failure, not a count -- rule 11: disabled after exactly one qualifying failure.
    assertThat(channelStatus(501)).isEqualTo(ChannelStatus.AUTO_DISABLED);
  }

  @Test
  public void aStatusNotInTheDisableSetLeavesTheChannelEnabled() {
    registerChannel(502, "disable-2", "gpt-4", true);
    UPSTREAM.answersWith(500); // retryable, but not in disable-on-status = [401]
    completions("disable-2", "gpt-4", "acct-disable-2");
    assertThat(channelStatus(502)).isEqualTo(ChannelStatus.ENABLED);
  }

  @Test
  public void aChannelWithAutoBanOffIsNeverDisabledEvenOnAQualifyingFailure() {
    registerChannel(503, "disable-3", "gpt-4", false);
    UPSTREAM.answersWith(401);
    completions("disable-3", "gpt-4", "acct-disable-3");
    assertThat(channelStatus(503)).isEqualTo(ChannelStatus.ENABLED);
  }

  @Test
  public void aDisabledChannelIsNoLongerSelected() {
    // Registered one at a time so the single attempt (max-retries=0) is guaranteed to
    // land on 504 first -- with both candidates present from the start, the tier's
    // weighted-random pick (SPEC-001 §3 rule 3) could have landed on either.
    registerChannel(504, "disable-4", "gpt-4", true);
    UPSTREAM.answersWith(401);
    completions("disable-4", "gpt-4", "acct-disable-4");
    assertThat(channelStatus(504)).isEqualTo(ChannelStatus.AUTO_DISABLED);

    registerChannel(505, "disable-4", "gpt-4", true); // the only enabled candidate now
    UPSTREAM.reset();
    UPSTREAM.answersWith(200);
    completions("disable-4", "gpt-4", "acct-disable-4b");
    assertThat(UPSTREAM.channelsSeen()).containsExactly("505");
  }
}
