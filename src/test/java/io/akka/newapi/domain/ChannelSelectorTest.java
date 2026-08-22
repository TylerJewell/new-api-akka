package io.akka.newapi.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SPEC-001 §3 rules 1-5, question-log #1, #2, #3 -- run against the real source (see
 * probes/source_probe/main.go) to establish these numbers before this test was written.
 */
public class ChannelSelectorTest {

  @Test
  public void noCandidatesSelectsNothing() {
    var selection = ChannelSelector.select(List.of(), 0, 0);
    assertThat(selection.channelId()).isEmpty();
    assertThat(selection.tierCount()).isZero();
  }

  @Test
  public void aSingleCandidateAtWeightZeroIsStillReachable() {
    // question-log #1: the +10 baseline makes a weight-0 channel selectable without any
    // special-casing of an all-zero tier.
    var selection = ChannelSelector.select(List.of(new ChannelCandidate(1, 100, 0, true)), 0, 0);
    assertThat(selection.channelId()).contains(1L);
  }

  @Test
  public void retryIndexZeroServesTheHighestPriorityTierOnly() {
    var candidates =
        List.of(new ChannelCandidate(1, 100, 0, true), new ChannelCandidate(2, 100, 0, true), new ChannelCandidate(3, 50, 0, true));
    for (long draw = 0; draw < 40; draw++) {
      var selection = ChannelSelector.select(candidates, 0, draw);
      assertThat(selection.channelId()).isPresent();
      assertThat(selection.channelId().get()).isIn(1L, 2L);
    }
  }

  @Test
  public void retryPastTheTierCountClampsToTheLowestTier() {
    // question-log #2: retry=1,2,5,100 all landed on the sole lowest-tier channel.
    var candidates = List.of(new ChannelCandidate(1, 100, 0, true), new ChannelCandidate(3, 50, 0, true));
    for (int retry : new int[] {1, 2, 5, 100}) {
      var selection = ChannelSelector.select(candidates, retry, 0);
      assertThat(selection.channelId()).contains(3L);
      assertThat(selection.tierCount()).isEqualTo(2);
    }
  }

  @Test
  public void weightSkewsSelectionProportionallyToWeightPlusTen() {
    // question-log #3 and #15: weight 90 against weight 0 in one tier, effective weights
    // 100 and 10 out of 110. The split is 101:9, not 100:10 -- the boundary draw belongs
    // to the heavier candidate, which is what the source's own walk does.
    var candidates = List.of(new ChannelCandidate(10, 100, 90, true), new ChannelCandidate(11, 100, 0, true));
    assertThat(ChannelSelector.select(candidates, 0, 0).channelId()).contains(10L);
    assertThat(ChannelSelector.select(candidates, 0, 99).channelId()).contains(10L);
    assertThat(ChannelSelector.select(candidates, 0, 100).channelId()).contains(10L);
    assertThat(ChannelSelector.select(candidates, 0, 101).channelId()).contains(11L);
    assertThat(ChannelSelector.select(candidates, 0, 109).channelId()).contains(11L);
    assertThat(ChannelSelector.select(candidates, 0, 110).channelId()).contains(10L); // wraps via floorMod
  }

  @Test
  public void theHeaviestCandidateGoesFirstWhateverOrderTheyArriveIn() {
    // The walk gives its first candidate one extra draw value, so which candidate is
    // first is part of the answer. Ordering by weight descending is how the source
    // reaches its candidates; ties fall to the lower id.
    var arrivedLightFirst = List.of(new ChannelCandidate(11, 100, 0, true), new ChannelCandidate(10, 100, 90, true));
    assertThat(ChannelSelector.select(arrivedLightFirst, 0, 100).channelId()).contains(10L);

    var tied = List.of(new ChannelCandidate(2, 100, 0, true), new ChannelCandidate(1, 100, 0, true));
    assertThat(ChannelSelector.select(tied, 0, 10).channelId()).contains(1L);
    assertThat(ChannelSelector.select(tied, 0, 11).channelId()).contains(2L);
  }

  @Test
  public void aChannelAlreadyTriedThisRequestCanBeReselected() {
    // question-log #10: the source keeps no exclusion set, so selection is memoryless --
    // ChannelSelector takes no "already tried" argument at all, by design.
    var candidates = List.of(new ChannelCandidate(1, 100, 0, true));
    assertThat(ChannelSelector.select(candidates, 0, 0).channelId()).contains(1L);
    assertThat(ChannelSelector.select(candidates, 1, 0).channelId()).contains(1L);
  }
}
