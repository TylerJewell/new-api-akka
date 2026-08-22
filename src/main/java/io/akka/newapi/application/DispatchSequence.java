package io.akka.newapi.application;

import akka.javasdk.client.ComponentClient;
import io.akka.newapi.domain.ChannelCandidate;
import io.akka.newapi.domain.ChannelSelector;
import io.akka.newapi.domain.ChannelState;
import io.akka.newapi.domain.DispatchOutcome;
import io.akka.newapi.domain.FailureClassifier;
import io.akka.newapi.domain.QuotaMath;
import io.akka.newapi.domain.RelayConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * One request's trip from admission through dispatch to settlement. SPEC-001 §3 rules
 * 1-17.
 *
 * <p>Selection is re-read from the entities on every attempt rather than snapshotted once
 * per request -- the point of taking a channel out of rotation is to stop sending it
 * traffic, and a snapshot taken before the ejection would keep sending it traffic for the
 * rest of the sequence (the same reasoning higress-port recorded for its credential
 * pool). Question-log #10 found the source itself keeps no exclusion set at all (SPEC-001
 * §4.4, §3 rule 4) -- this port does not add one either.
 */
public class DispatchSequence {

  private final ComponentClient componentClient;
  private final Upstream upstream;
  private final RelayConfig config;
  private final LongSupplier randomDraw;

  public DispatchSequence(ComponentClient componentClient, Upstream upstream, RelayConfig config) {
    this(componentClient, upstream, config, () -> java.util.concurrent.ThreadLocalRandom.current().nextLong(Long.MAX_VALUE));
  }

  /** Package-visible: tests pass a fixed draw for a deterministic pick (SPEC-001 §3 rule 3). */
  DispatchSequence(ComponentClient componentClient, Upstream upstream, RelayConfig config, LongSupplier randomDraw) {
    this.componentClient = componentClient;
    this.upstream = upstream;
    this.config = config;
    this.randomDraw = randomDraw;
  }

  public DispatchOutcome dispatch(
      String accountId, String group, String model, long promptTokens, long declaredMaxOutputTokens, String body) {
    long preConsumeQuota =
        QuotaMath.preConsume(
            promptTokens, declaredMaxOutputTokens, config.minPreConsumeTokens(), config.modelRatio(), config.groupRatio());

    var reserve =
        componentClient
            .forKeyValueEntity(accountId)
            .method(AccountEntity::reserve)
            .invoke(new AccountEntity.Reserve(preConsumeQuota));
    if (!reserve.reserved()) {
      return new DispatchOutcome(402, "{\"error\":\"insufficient quota\"}", 0, List.of(), DispatchOutcome.StoppedBecause.INSUFFICIENT_QUOTA, 0, 0);
    }

    var channelsUsed = new ArrayList<Long>();
    Upstream.Answer lastAnswer = null;

    for (int retryIndex = 0; retryIndex <= config.maxRetries(); retryIndex++) {
      var candidates = candidatesFor(group, model);
      var selection = ChannelSelector.select(candidates, retryIndex, randomDraw.getAsLong());
      if (selection.channelId().isEmpty()) {
        refund(accountId, preConsumeQuota);
        return outcome(lastAnswer, channelsUsed, DispatchOutcome.StoppedBecause.NO_CHANNEL_AVAILABLE, preConsumeQuota, 0);
      }
      long channelId = selection.channelId().get();
      boolean selectedAutoBan = candidates.stream().filter(c -> c.id() == channelId).findFirst().orElseThrow().autoBan();
      channelsUsed.add(channelId);

      lastAnswer = upstream.call(channelId, body);

      if (lastAnswer.status() >= 200 && lastAnswer.status() < 300) {
        long actualQuota =
            QuotaMath.settle(
                lastAnswer.promptTokens(), lastAnswer.completionTokens(), config.completionRatio(), config.modelRatio(), config.groupRatio());
        long delta = actualQuota - preConsumeQuota;
        componentClient
            .forKeyValueEntity(accountId)
            .method(AccountEntity::settle)
            .invoke(new AccountEntity.Settle(delta));
        return outcome(lastAnswer, channelsUsed, DispatchOutcome.StoppedBecause.SUCCESS, preConsumeQuota, actualQuota);
      }

      // Rule 10 gates on the global switch first: with auto-disable off -- the default --
      // no failure can change any channel's status, so the command is not sent at all.
      if (config.autoDisableEnabled()) {
        boolean statusQualifies = FailureClassifier.qualifiesForAutoDisable(
            lastAnswer.status(), lastAnswer.channelError(), true, selectedAutoBan, config.disableOnStatus());
        if (statusQualifies) {
          componentClient
              .forKeyValueEntity(Long.toString(channelId))
              .method(ChannelEntity::disable)
              .invoke(new ChannelEntity.Disable(true, lastAnswer.channelError(), true));
        }
      }

      boolean retryable = FailureClassifier.isRetryable(lastAnswer.status(), lastAnswer.channelError(), config.retryOnStatus());
      if (!retryable || retryIndex == config.maxRetries()) {
        refund(accountId, preConsumeQuota);
        return outcome(lastAnswer, channelsUsed, DispatchOutcome.StoppedBecause.ATTEMPTS_EXHAUSTED, preConsumeQuota, 0);
      }
    }
    // Unreachable: the loop above always returns before falling off its bound.
    refund(accountId, preConsumeQuota);
    return outcome(lastAnswer, channelsUsed, DispatchOutcome.StoppedBecause.ATTEMPTS_EXHAUSTED, preConsumeQuota, 0);
  }

  private void refund(String accountId, long amount) {
    componentClient.forKeyValueEntity(accountId).method(AccountEntity::refund).invoke(new AccountEntity.Refund(amount));
  }

  private List<ChannelCandidate> candidatesFor(String group, String model) {
    var index =
        componentClient
            .forKeyValueEntity(group + "::" + model)
            .method(AbilityIndexEntity::get)
            .invoke();
    var candidates = new ArrayList<ChannelCandidate>();
    for (long channelId : index.channelIds()) {
      var channel = readChannel(channelId);
      if (channel.isEnabled()) {
        candidates.add(new ChannelCandidate(channel.id(), channel.priority(), channel.weight(), channel.autoBan()));
      }
    }
    return candidates;
  }

  private ChannelState readChannel(long channelId) {
    return componentClient.forKeyValueEntity(Long.toString(channelId)).method(ChannelEntity::get).invoke();
  }

  private DispatchOutcome outcome(
      Upstream.Answer answer,
      List<Long> channelsUsed,
      DispatchOutcome.StoppedBecause reason,
      long preConsumed,
      long settled) {
    if (answer == null) {
      return new DispatchOutcome(503, "{\"error\":\"no channel available\"}", 0, List.copyOf(channelsUsed), reason, preConsumed, settled);
    }
    return new DispatchOutcome(answer.status(), answer.body(), channelsUsed.size(), List.copyOf(channelsUsed), reason, preConsumed, settled);
  }
}
