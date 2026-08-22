package io.akka.newapi.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.keyvalueentity.KeyValueEntity;
import io.akka.newapi.domain.AccountState;

/**
 * One wallet balance. The entity id is the account id. SPEC-001 §2, §3 rules 13-17.
 *
 * <p>{@code reserve}'s atomicity comes from the runtime serialising every command against
 * one entity instance -- the same guarantee the source gets from a conditional SQL
 * {@code UPDATE ... WHERE balance >= ?} (question-log #4), reached by a different
 * mechanism (SPEC-001 §4.5).
 */
@Component(id = "account")
public class AccountEntity extends KeyValueEntity<AccountState> {

  public record Reserve(long amount) {}

  public record Settle(long delta) {}

  public record Refund(long amount) {}

  public record Deposit(long amount) {}

  @Override
  public AccountState emptyState() {
    return AccountState.empty();
  }

  public Effect<AccountState.ReserveResult> reserve(Reserve command) {
    var result = currentState().reserve(command.amount());
    return result.reserved()
        ? effects().updateState(result.state()).thenReply(result)
        : effects().reply(result);
  }

  public Effect<AccountState> settle(Settle command) {
    var state = currentState().settle(command.delta());
    return effects().updateState(state).thenReply(state);
  }

  public Effect<AccountState> refund(Refund command) {
    var state = currentState().refund(command.amount());
    return effects().updateState(state).thenReply(state);
  }

  public Effect<AccountState> deposit(Deposit command) {
    var state = currentState().deposit(command.amount());
    return effects().updateState(state).thenReply(state);
  }

  public ReadOnlyEffect<AccountState> get() {
    return effects().reply(currentState());
  }
}
