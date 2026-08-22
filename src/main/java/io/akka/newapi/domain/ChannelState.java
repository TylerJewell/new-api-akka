package io.akka.newapi.domain;

import java.util.Set;

/**
 * One upstream credential slot. SPEC-001 §2. The entity id is the channel id.
 *
 * <p>{@code weight} of {@code 0} is a normal, reachable value -- ChannelSelector adds a
 * fixed baseline to every candidate's weight rather than special-casing an all-zero tier
 * (question-log #1, #3).
 */
public record ChannelState(
    long id,
    Set<String> groups,
    Set<String> models,
    long priority,
    int weight,
    ChannelStatus status,
    boolean autoBan) {

  public static ChannelState unknown(long id) {
    return new ChannelState(id, Set.of(), Set.of(), 0, 0, ChannelStatus.ENABLED, true);
  }

  public boolean isEnabled() {
    return status == ChannelStatus.ENABLED;
  }

  public ChannelState withStatus(ChannelStatus newStatus) {
    return new ChannelState(id, groups, models, priority, weight, newStatus, autoBan);
  }
}
