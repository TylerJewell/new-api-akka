package io.akka.newapi.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.newapi.domain.ChannelState;
import io.akka.newapi.domain.ChannelStatus;
import java.util.Set;

/**
 * One upstream credential slot. The entity id is the channel id. SPEC-001 §2, §3 rules
 * 1, 10-12.
 */
@Component(id = "channel")
public class ChannelEntity extends KeyValueEntity<ChannelState> {

  public record Register(Set<String> groups, Set<String> models, long priority, int weight, boolean autoBan) {}

  public record Disable(boolean autoDisableEnabled, boolean isChannelError, boolean statusQualifies) {}

  // commandContext() is only valid while a command is being handled, so the id -- known
  // only from the entity id, not from any command payload -- is resolved in register()
  // rather than here; emptyState() runs outside that window (e.g. equality checks).
  @Override
  public ChannelState emptyState() {
    return ChannelState.unknown(0);
  }

  public Effect<Done> register(Register command) {
    var state =
        new ChannelState(
            Long.parseLong(commandContext().entityId()),
            command.groups(),
            command.models(),
            command.priority(),
            command.weight(),
            currentState().status(),
            command.autoBan());
    return effects().updateState(state).thenReply(Done.getInstance());
  }

  /**
   * Rule 11: disables on the first qualifying failure, no consecutive-failure count.
   * Rule 10: gated by both the global switch (passed in, since it is deployment
   * configuration, not channel state) and this channel's own autoBan flag.
   */
  public Effect<Done> disable(Disable command) {
    var state = currentState();
    boolean qualifies = command.isChannelError() || command.statusQualifies();
    if (!command.autoDisableEnabled() || !state.autoBan() || !qualifies || !state.isEnabled()) {
      return effects().reply(Done.getInstance());
    }
    return effects()
        .updateState(state.withStatus(ChannelStatus.AUTO_DISABLED))
        .thenReply(Done.getInstance());
  }

  public Effect<Done> enable() {
    return effects().updateState(currentState().withStatus(ChannelStatus.ENABLED)).thenReply(Done.getInstance());
  }

  public ReadOnlyEffect<ChannelState> get() {
    return effects().reply(currentState());
  }
}
