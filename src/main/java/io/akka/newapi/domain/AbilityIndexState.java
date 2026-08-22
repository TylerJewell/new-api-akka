package io.akka.newapi.domain;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The set of channel ids registered for one {@code (group, model)} pair. SPEC-001 §2.
 * The entity id is the pair, formatted {@code group + "::" + model} ({@code "|"} is a
 * reserved character in Akka entity ids and cannot be used here).
 *
 * <p>Mirrors the source's {@code abilities} table, which is itself an index row per
 * {@code (group, model, channelId)} rather than a property of the channel. Enablement is
 * not duplicated here: selection reads each candidate's {@link ChannelState} directly
 * (SPEC-001 §3 rule 1), the same way the source re-reads {@code channels.status} rather
 * than trusting a cached flag on the ability row.
 */
public record AbilityIndexState(Set<Long> channelIds) {

  public static AbilityIndexState empty() {
    return new AbilityIndexState(Set.of());
  }

  public AbilityIndexState withChannel(long channelId) {
    if (channelIds.contains(channelId)) {
      return this;
    }
    var next = new LinkedHashSet<>(channelIds);
    next.add(channelId);
    return new AbilityIndexState(Set.copyOf(next));
  }

  public AbilityIndexState withoutChannel(long channelId) {
    if (!channelIds.contains(channelId)) {
      return this;
    }
    var next = new LinkedHashSet<>(channelIds);
    next.remove(channelId);
    return new AbilityIndexState(Set.copyOf(next));
  }
}
