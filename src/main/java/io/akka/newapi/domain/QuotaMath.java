package io.akka.newapi.domain;

/**
 * The two quota formulas this port ports: pre-consume (before the answer is known) and
 * settle (after it is). SPEC-001 §3 rules 13, 15.
 *
 * <p>Both truncate toward zero, matching the source's {@code int(value)} conversion in
 * {@code common.QuotaFromFloatStrict} (never rounds up) -- see question-log #11.
 */
public final class QuotaMath {

  private QuotaMath() {}

  /** Rule 13: floors promptTokens at a configured minimum before applying the ratios. */
  public static long preConsume(
      long promptTokens, long declaredMaxOutputTokens, long minPreConsumeTokens, double modelRatio, double groupRatio) {
    long billedPromptTokens = Math.max(promptTokens, minPreConsumeTokens);
    double raw = (billedPromptTokens + declaredMaxOutputTokens) * modelRatio * groupRatio;
    return (long) raw;
  }

  /** Rule 15: the actual cost, once prompt and completion tokens are both known. */
  public static long settle(
      long promptTokens, long completionTokens, double completionRatio, double modelRatio, double groupRatio) {
    double raw = (promptTokens + completionTokens * completionRatio) * modelRatio * groupRatio;
    return (long) raw;
  }
}
