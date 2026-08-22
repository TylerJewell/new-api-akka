package io.akka.newapi.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** SPEC-001 §3 rules 14-17, question-log #4 (the source's equivalent, run under concurrency). */
public class AccountStateTest {

  @Test
  public void reserveSucceedsWhenTheBalanceCovers() {
    var result = new AccountState(100).reserve(30);
    assertThat(result.reserved()).isTrue();
    assertThat(result.state().balance()).isEqualTo(70);
  }

  @Test
  public void reserveFailsAndLeavesTheBalanceUntouchedWhenItDoesNot() {
    var result = new AccountState(10).reserve(30);
    assertThat(result.reserved()).isFalse();
    assertThat(result.state().balance()).isEqualTo(10);
  }

  @Test
  public void reserveAtExactlyTheBalanceSucceeds() {
    var result = new AccountState(30).reserve(30);
    assertThat(result.reserved()).isTrue();
    assertThat(result.state().balance()).isZero();
  }

  @Test
  public void settlePositiveDeltaChargesMore() {
    assertThat(new AccountState(70).settle(10).balance()).isEqualTo(60);
  }

  @Test
  public void settleNegativeDeltaRefunds() {
    assertThat(new AccountState(70).settle(-10).balance()).isEqualTo(80);
  }

  @Test
  public void refundRestoresTheFullReservedAmount() {
    assertThat(new AccountState(70).refund(30).balance()).isEqualTo(100);
  }
}
