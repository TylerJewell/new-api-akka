package io.akka.newapi.domain;

/**
 * One wallet balance. SPEC-001 §2, §3 rules 13-17. The entity id is the account id.
 *
 * <p>{@code reserve} is the atomic check-and-deduct that gives rule 14 its guarantee: the
 * runtime serialises every command against one entity instance, so two concurrent reserve
 * calls against a balance that can satisfy only one of them can never both succeed --
 * question-log #4 ran the source's equivalent (a conditional SQL {@code UPDATE}) under
 * concurrency and got the same result this reproduces by construction.
 */
public record AccountState(long balance) {

  public static AccountState empty() {
    return new AccountState(0);
  }

  public record ReserveResult(boolean reserved, AccountState state) {}

  public ReserveResult reserve(long amount) {
    if (amount <= 0) {
      return new ReserveResult(true, this);
    }
    if (balance < amount) {
      return new ReserveResult(false, this);
    }
    return new ReserveResult(true, new AccountState(balance - amount));
  }

  /** Settle a signed delta against a prior reservation: positive charges, negative refunds. */
  public AccountState settle(long delta) {
    return new AccountState(balance - delta);
  }

  public AccountState refund(long amount) {
    return new AccountState(balance + amount);
  }

  /** Funding, not a reversal: separate from refund so a change to one cannot move the other. */
  public AccountState deposit(long amount) {
    return new AccountState(balance + amount);
  }
}
