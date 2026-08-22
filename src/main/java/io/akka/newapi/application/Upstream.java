package io.akka.newapi.application;

/** The provider this gateway dispatches to, once a channel has been selected. */
public interface Upstream {

  Answer call(long channelId, String body);

  /**
   * @param status the HTTP-shaped status the provider answered with.
   * @param promptTokens tokens the answer reports as consumed on input.
   * @param completionTokens tokens the answer reports as consumed on output.
   * @param channelError whether this failure belongs to the channel itself (auth,
   *     connectivity) rather than to the specific answer given -- SPEC-001 §3 rule 7, 11.
   */
  record Answer(int status, String body, long promptTokens, long completionTokens, boolean channelError) {}
}
