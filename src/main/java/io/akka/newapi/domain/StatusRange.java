package io.akka.newapi.domain;

/** An inclusive HTTP status code range, parsed from config as {@code "start-end"}. */
public record StatusRange(int start, int end) {

  public boolean contains(int code) {
    return code >= start && code <= end;
  }

  public static StatusRange parse(String spec) {
    var parts = spec.split("-", 2);
    return new StatusRange(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
  }
}
