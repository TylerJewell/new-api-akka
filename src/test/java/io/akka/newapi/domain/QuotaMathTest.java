package io.akka.newapi.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rules 13, 15, question-log #11. */
public class QuotaMathTest {

  @Test
  public void preConsumeFloorsAShortPromptAtTheConfiguredMinimum() {
    // 50 prompt tokens is below the 500 floor, so the floor applies -- (500 + 0) * 1 * 1.
    assertThat(QuotaMath.preConsume(50, 0, 500, 1.0, 1.0)).isEqualTo(500);
  }

  @Test
  public void preConsumeUsesTheRealPromptCountOnceItExceedsTheFloor() {
    assertThat(QuotaMath.preConsume(800, 0, 500, 1.0, 1.0)).isEqualTo(800);
  }

  @Test
  public void preConsumeAddsTheDeclaredMaxOutputTokens() {
    assertThat(QuotaMath.preConsume(800, 200, 500, 1.0, 1.0)).isEqualTo(1000);
  }

  @Test
  public void preConsumeAppliesModelAndGroupRatios() {
    assertThat(QuotaMath.preConsume(1000, 0, 500, 2.0, 0.5)).isEqualTo(1000);
  }

  @Test
  public void preConsumeTruncatesTowardZeroRatherThanRounding() {
    assertThat(QuotaMath.preConsume(999, 0, 500, 1.0, 1.0)).isEqualTo(999);
    assertThat(QuotaMath.preConsume(999, 0, 500, 1.0009, 1.0)).isEqualTo(999); // 999*1.0009 = 999.899...
  }

  @Test
  public void settleAppliesTheCompletionRatioOnlyToCompletionTokens() {
    // (100 prompt + 50 completion * 2.0 completionRatio) * 1 * 1 = 200
    assertThat(QuotaMath.settle(100, 50, 2.0, 1.0, 1.0)).isEqualTo(200);
  }

  @Test
  public void settleHasNoPromptFloorUnlikePreConsume() {
    assertThat(QuotaMath.settle(10, 0, 1.0, 1.0, 1.0)).isEqualTo(10);
  }
}
