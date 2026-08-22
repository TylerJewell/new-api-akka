package io.akka.newapi;

import static org.assertj.core.api.Assertions.assertThat;

import akka.http.javadsl.model.ContentTypes;
import akka.javasdk.http.StrictResponse;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.akka.newapi.application.AbilityIndexEntity;
import io.akka.newapi.application.AccountEntity;
import io.akka.newapi.application.ChannelEntity;
import io.akka.newapi.domain.AccountState;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §3 rules 13-17 with the runtime running: reserve, settle and refund, driven
 * through the gateway's own HTTP surface rather than by calling AccountEntity directly.
 */
public class BillingIntegrationTest extends TestKitSupport {

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
            new-api.dispatch.max-retries = 1
            new-api.pricing.model-ratio = 1.0
            new-api.pricing.group-ratio = 1.0
            new-api.pricing.completion-ratio = 2.0
            new-api.pricing.min-pre-consume-tokens = 500
            new-api.auto-disable.enabled = false
            """)
        .withMockedHttpService("upstream", UPSTREAM::answer);
  }

  private void registerChannel(long id, String group, String model) {
    componentClient
        .forKeyValueEntity(Long.toString(id))
        .method(ChannelEntity::register)
        .invoke(new ChannelEntity.Register(Set.of(group), Set.of(model), 100, 0, true));
    componentClient
        .forKeyValueEntity(group + "::" + model)
        .method(AbilityIndexEntity::addChannel)
        .invoke(new AbilityIndexEntity.ChannelId(id));
  }

  private AccountState deposit(String accountId, long amount) {
    return componentClient
        .forKeyValueEntity(accountId)
        .method(AccountEntity::deposit)
        .invoke(new AccountEntity.Deposit(amount));
  }

  private AccountState account(String accountId) {
    return componentClient.forKeyValueEntity(accountId).method(AccountEntity::get).invoke();
  }

  private StrictResponse<String> completions(String group, String model, String accountId, long promptTokens) {
    var body =
        "{\"account_id\":\""
            + accountId
            + "\",\"group\":\""
            + group
            + "\",\"model\":\""
            + model
            + "\",\"prompt_tokens\":"
            + promptTokens
            + "}";
    return httpClient
        .POST("/chat/completions")
        .withRequestBody(ContentTypes.APPLICATION_JSON, body.getBytes(StandardCharsets.UTF_8))
        .parseResponseBody(b -> new String(b, StandardCharsets.UTF_8))
        .invoke();
  }

  private static String header(StrictResponse<String> response, String name) {
    return response.httpResponse().getHeader(name).map(h -> h.value()).orElse("(absent)");
  }

  @Test
  public void aShortPromptStillReservesTheFloor() {
    registerChannel(401, "billing", "gpt-4");
    deposit("acct-floor", 10_000);
    UPSTREAM.answersWith(200);
    UPSTREAM.reportsTokens(10, 0);

    var response = completions("billing", "gpt-4", "acct-floor", 5); // below the 500 floor
    assertThat(header(response, "X-Preconsumed-Quota")).isEqualTo("500");
  }

  @Test
  public void aSuccessfulCallSettlesToActualUsageNotThePreConsumedEstimate() {
    registerChannel(402, "billing", "gpt-4");
    deposit("acct-settle", 10_000);
    UPSTREAM.answersWith(200);
    UPSTREAM.reportsTokens(500, 100); // prompt matches the floor exactly

    var response = completions("billing", "gpt-4", "acct-settle", 500);
    // pre-consumed = (500 + 0) * 1 * 1 = 500; settled = (500 + 100*2.0) * 1 * 1 = 700
    assertThat(header(response, "X-Preconsumed-Quota")).isEqualTo("500");
    assertThat(header(response, "X-Settled-Quota")).isEqualTo("700");
    assertThat(account("acct-settle").balance()).isEqualTo(10_000 - 700);
  }

  @Test
  public void aRequestThatUsesFewerTokensThanEstimatedRefundsTheDifference() {
    registerChannel(403, "billing", "gpt-4");
    deposit("acct-overestimate", 10_000);
    UPSTREAM.answersWith(200);
    UPSTREAM.reportsTokens(500, 0); // settled quota = 500, same as pre-consumed here

    completions("billing", "gpt-4", "acct-overestimate", 2_000); // pre-consume = 2000
    // pre-consumed = 2000, settled = 500 -> delta = -1500, balance = 10000 - 2000 - (-1500) = 9500
    assertThat(account("acct-overestimate").balance()).isEqualTo(9_500);
  }

  @Test
  public void everyAttemptFailingRefundsTheFullPreConsumedAmount() {
    registerChannel(404, "billing", "gpt-4");
    deposit("acct-refund", 10_000);
    UPSTREAM.answersWith(500, 500); // max-retries=1 -> two attempts, both fail

    var response = completions("billing", "gpt-4", "acct-refund", 1_000);
    assertThat(header(response, "X-Stopped-Because")).isEqualTo("ATTEMPTS_EXHAUSTED");
    assertThat(account("acct-refund").balance()).isEqualTo(10_000);
  }

  @Test
  public void insufficientBalanceRefusesWithoutDispatchingAnything() {
    registerChannel(405, "billing", "gpt-4");
    deposit("acct-poor", 100);
    UPSTREAM.answersWith(200);

    var response = completions("billing", "gpt-4", "acct-poor", 1_000); // needs 1000 > 100 balance
    assertThat(response.status().intValue()).isEqualTo(402);
    // A refusal for money is reported as one: NO_CHANNEL_AVAILABLE would tell a caller
    // to look at channel configuration for a problem that is entirely in the wallet.
    assertThat(header(response, "X-Stopped-Because")).isEqualTo("INSUFFICIENT_QUOTA");
    assertThat(UPSTREAM.channelsSeen()).isEmpty();
    assertThat(account("acct-poor").balance()).isEqualTo(100);
  }
}
