package io.akka.newapi.domain;

import java.util.List;

/** The result of one request's full dispatch sequence. SPEC-001 §2. */
public record DispatchOutcome(
    int status,
    String body,
    int attempts,
    List<Long> channelsUsed,
    StoppedBecause stoppedBecause,
    long preConsumedQuota,
    long settledQuota) {

  public enum StoppedBecause {
    SUCCESS,
    ATTEMPTS_EXHAUSTED,
    NO_CHANNEL_AVAILABLE,
    INSUFFICIENT_QUOTA
  }

  public boolean succeeded() {
    return stoppedBecause == StoppedBecause.SUCCESS;
  }
}
