package io.akka.newapi.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rules 6-11, question-log #6, #7, #8, #9. */
public class FailureClassifierTest {

  private static final List<StatusRange> DEFAULT_RETRY =
      List.of(
          new StatusRange(100, 199), new StatusRange(300, 399), new StatusRange(401, 407),
          new StatusRange(409, 499), new StatusRange(500, 503), new StatusRange(505, 523),
          new StatusRange(525, 599));
  private static final List<StatusRange> DEFAULT_DISABLE = List.of(new StatusRange(401, 401));

  @Test
  public void aChannelErrorIsAlwaysRetriedRegardlessOfStatus() {
    assertThat(FailureClassifier.isRetryable(200, true, DEFAULT_RETRY)).isTrue();
    assertThat(FailureClassifier.isRetryable(400, true, DEFAULT_RETRY)).isTrue();
  }

  @Test
  public void a2xxIsNotRetriedOnTheOrdinaryPath() {
    assertThat(FailureClassifier.isRetryable(200, false, DEFAULT_RETRY)).isFalse();
  }

  @Test
  public void aChannelErrorCarryingA2xxIsStillRetried() {
    // Rule 7 is unconditional and is tested before rule 8's 2xx exclusion, so the two
    // rules meet here and rule 7 wins. The dispatch sequence never reaches this
    // combination -- it treats any 2xx as a success before classifying anything -- so
    // the resolution is only ever visible to this classifier's direct callers.
    assertThat(FailureClassifier.isRetryable(200, true, DEFAULT_RETRY)).isTrue();
  }

  @Test
  public void theDefaultTableRetriesEverythingExcept2xx400408504524() {
    for (int code : new int[] {100, 199, 300, 399, 401, 407, 409, 499, 500, 503, 505, 523, 525, 599}) {
      assertThat(FailureClassifier.isRetryable(code, false, DEFAULT_RETRY)).as("code %d", code).isTrue();
    }
    for (int code : new int[] {200, 201, 204, 400, 408}) {
      assertThat(FailureClassifier.isRetryable(code, false, DEFAULT_RETRY)).as("code %d", code).isFalse();
    }
  }

  @Test
  public void statusesOutsideEveryRangeAreNotRetried() {
    // 504 and 524 fall in the gaps deliberately left in DEFAULT_RETRY (500-503, 505-523,
    // 525-599 skip exactly these two) -- matching the source's alwaysSkipRetryStatusCodes.
    assertThat(FailureClassifier.isRetryable(504, false, DEFAULT_RETRY)).isFalse();
    assertThat(FailureClassifier.isRetryable(524, false, DEFAULT_RETRY)).isFalse();
  }

  @Test
  public void autoDisableRequiresBothTheGlobalSwitchAndTheChannelFlag() {
    assertThat(FailureClassifier.qualifiesForAutoDisable(401, false, false, true, DEFAULT_DISABLE)).isFalse();
    assertThat(FailureClassifier.qualifiesForAutoDisable(401, false, true, false, DEFAULT_DISABLE)).isFalse();
    assertThat(FailureClassifier.qualifiesForAutoDisable(401, false, true, true, DEFAULT_DISABLE)).isTrue();
  }

  @Test
  public void onlyTheConfiguredStatusesQualifyForAutoDisableByStatus() {
    assertThat(FailureClassifier.qualifiesForAutoDisable(500, false, true, true, DEFAULT_DISABLE)).isFalse();
    assertThat(FailureClassifier.qualifiesForAutoDisable(401, false, true, true, DEFAULT_DISABLE)).isTrue();
  }

  @Test
  public void aChannelErrorQualifiesForAutoDisableRegardlessOfStatus() {
    assertThat(FailureClassifier.qualifiesForAutoDisable(200, true, true, true, DEFAULT_DISABLE)).isTrue();
  }
}
