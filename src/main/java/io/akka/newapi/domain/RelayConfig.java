package io.akka.newapi.domain;

import com.typesafe.config.Config;
import java.util.List;


/** The deployment's dispatch, pricing and auto-disable settings, from {@code application.conf}. */
public record RelayConfig(
    String upstreamService,
    int maxRetries,
    double modelRatio,
    double completionRatio,
    double groupRatio,
    long minPreConsumeTokens,
    List<StatusRange> retryOnStatus,
    boolean autoDisableEnabled,
    List<StatusRange> disableOnStatus) {

  // An endpoint is constructed per request, so parsing is cached against the Config
  // instance the runtime hands in -- compared by identity, since that instance is the same
  // object for the life of a service and comparing Config by value walks the whole tree.
  private static volatile Config lastRoot;
  private static volatile RelayConfig lastParsed;

  public static RelayConfig of(Config root) {
    if (lastRoot == root) {
      return lastParsed;
    }
    var parsed = from(root);
    lastParsed = parsed;
    lastRoot = root;
    return parsed;
  }

  public static RelayConfig from(Config root) {
    var c = root.getConfig("new-api");
    var dispatch = c.getConfig("dispatch");
    var pricing = c.getConfig("pricing");
    var autoDisable = c.getConfig("auto-disable");
    return new RelayConfig(
        c.getString("upstream-service"),
        dispatch.getInt("max-retries"),
        pricing.getDouble("model-ratio"),
        pricing.getDouble("completion-ratio"),
        pricing.getDouble("group-ratio"),
        pricing.getLong("min-pre-consume-tokens"),
        c.getStringList("retry-on-status-ranges").stream().map(StatusRange::parse).toList(),
        autoDisable.getBoolean("enabled"),
        autoDisable.getIntList("disable-on-status").stream().map(code -> new StatusRange(code, code)).toList());
  }
}
