package io.akka.newapi.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.newapi.domain.AbilityIndexState;

/**
 * The set of channel ids registered for one {@code (group, model)} pair. The entity id is
 * {@code group + "::" + model}. SPEC-001 §2, §3 rule 1, mirrors the source's {@code
 * abilities} table.
 */
@Component(id = "ability-index")
public class AbilityIndexEntity extends KeyValueEntity<AbilityIndexState> {

  public record ChannelId(long channelId) {}

  @Override
  public AbilityIndexState emptyState() {
    return AbilityIndexState.empty();
  }

  public Effect<Done> addChannel(ChannelId command) {
    var state = currentState().withChannel(command.channelId());
    return state.equals(currentState())
        ? effects().reply(Done.getInstance())
        : effects().updateState(state).thenReply(Done.getInstance());
  }

  public Effect<Done> removeChannel(ChannelId command) {
    var state = currentState().withoutChannel(command.channelId());
    return state.equals(currentState())
        ? effects().reply(Done.getInstance())
        : effects().updateState(state).thenReply(Done.getInstance());
  }

  public ReadOnlyEffect<AbilityIndexState> get() {
    return effects().reply(currentState());
  }
}
