package io.akka.newapi.domain;

import java.util.List;

/**
 * Whether a failed attempt should be retried, and whether it should disable the channel
 * it just used. SPEC-001 §3 rules 6-11.
 *
 * <p>A "channel error" (a fault belonging to the channel itself -- authentication or
 * connectivity -- rather than to the specific answer an upstream gave) always retries and
 * always qualifies for auto-disable, ahead of and regardless of any status-code table
 * (question-log #9). Everything else is decided purely by configured status-code ranges.
 */
public final class FailureClassifier {

  private FailureClassifier() {}

  public static boolean isRetryable(int statusCode, boolean isChannelError, List<StatusRange> retryOnStatus) {
    if (isChannelError) {
      return true;
    }
    if (statusCode >= 200 && statusCode < 300) {
      return false;
    }
    return retryOnStatus.stream().anyMatch(r -> r.contains(statusCode));
  }

  /** Auto-disable is gated by two independent flags that must both be true (rule 10). */
  public static boolean qualifiesForAutoDisable(
      int statusCode,
      boolean isChannelError,
      boolean autoDisableEnabled,
      boolean channelAutoBan,
      List<StatusRange> disableOnStatus) {
    if (!autoDisableEnabled || !channelAutoBan) {
      return false;
    }
    if (isChannelError) {
      return true;
    }
    return disableOnStatus.stream().anyMatch(r -> r.contains(statusCode));
  }
}
