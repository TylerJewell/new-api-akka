package io.akka.newapi.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Priority-tiered, weighted-random channel selection. SPEC-001 §3 rules 1-5.
 *
 * <p>Ports {@code model/ability.go:GetChannel}, the source's <em>default</em> selection
 * path (question-log #1) -- not the memory-cache path, which special-cases an all-zero-
 * weight tier differently (SPEC-001 §4.2). Every candidate's effective weight is
 * {@code weight + 10}, unconditionally, so a weight of {@code 0} is always reachable
 * without a separate branch.
 */
public final class ChannelSelector {

  private ChannelSelector() {}

  /** {@code tierCount} is 0 when there were no candidates at all. */
  public record Selection(Optional<Long> channelId, int tierCount) {
    public static final Selection NONE = new Selection(Optional.empty(), 0);
  }

  /**
   * @param retryIndex which priority tier to serve, clamped to the lowest tier once it
   *     reaches or exceeds the number of distinct tiers (SPEC-001 §3 rule 2).
   * @param randomDraw a caller-supplied non-negative value; only {@code draw mod
   *     totalWeight} matters, so tests can pass a fixed value for a deterministic pick.
   */
  public static Selection select(List<ChannelCandidate> candidates, int retryIndex, long randomDraw) {
    if (candidates.isEmpty()) {
      return Selection.NONE;
    }
    long targetPriority = Long.MIN_VALUE;
    var distinctPriorities = new java.util.TreeSet<Long>(Comparator.reverseOrder());
    for (var candidate : candidates) {
      distinctPriorities.add(candidate.priority());
    }
    int tierIndex = Math.min(Math.max(retryIndex, 0), distinctPriorities.size() - 1);
    int seen = 0;
    for (long priority : distinctPriorities) {
      if (seen++ == tierIndex) {
        targetPriority = priority;
        break;
      }
    }
    var tierCandidates = new java.util.ArrayList<ChannelCandidate>(candidates.size());
    for (var candidate : candidates) {
      if (candidate.priority() == targetPriority) {
        tierCandidates.add(candidate);
      }
    }
    // Heaviest first, ties by ascending id. The order is part of the answer, not a
    // presentation choice: the walk below hands the first candidate one extra draw value
    // and takes one from the last.
    tierCandidates.sort(
        Comparator.comparingInt(ChannelCandidate::weight).reversed().thenComparingLong(ChannelCandidate::id));

    long totalWeight = 0;
    for (var candidate : tierCandidates) {
      totalWeight += candidate.weight() + 10L;
    }
    // The draw lands in [0, totalWeight - 1] and a candidate wins when subtracting its
    // effective weight brings the remainder to zero or below -- so the boundary value
    // belongs to the candidate before it. The first candidate therefore covers
    // weight + 11 of the range and the last covers weight + 9.
    long remaining = Math.floorMod(randomDraw, totalWeight);
    for (var candidate : tierCandidates) {
      remaining -= candidate.weight() + 10L;
      if (remaining <= 0) {
        return new Selection(Optional.of(candidate.id()), distinctPriorities.size());
      }
    }
    // Unreachable: the remainder starts below totalWeight and every effective weight is
    // at least 10, so it crosses zero on or before the last candidate.
    var last = tierCandidates.get(tierCandidates.size() - 1);
    return new Selection(Optional.of(last.id()), distinctPriorities.size());
  }
}
