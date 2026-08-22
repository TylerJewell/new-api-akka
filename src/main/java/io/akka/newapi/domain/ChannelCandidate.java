package io.akka.newapi.domain;

/**
 * One selectable channel, as the dispatch sequence needs to see it.
 *
 * <p>{@code autoBan} rides along with the fields selection uses: the failure path needs it
 * for rule 10 on the same channel selection just returned, and it is read from the same
 * entity in the same attempt.
 */
public record ChannelCandidate(long id, long priority, int weight, boolean autoBan) {}
