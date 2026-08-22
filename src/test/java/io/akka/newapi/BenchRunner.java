package io.akka.newapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.akka.newapi.domain.AccountState;
import io.akka.newapi.domain.ChannelCandidate;
import io.akka.newapi.domain.ChannelSelector;
import io.akka.newapi.domain.FailureClassifier;
import io.akka.newapi.domain.QuotaMath;
import io.akka.newapi.domain.StatusRange;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Runs {@code bench/workloads.json} against this port's own decision code and writes the
 * answers and timings the benchmark compares against the source runner's.
 *
 * <p>Every rule under comparison is decided here by the same classes the service uses --
 * {@link ChannelSelector}, {@link FailureClassifier}, {@link QuotaMath}, {@link
 * AccountState}. What is not used is the Akka runtime: entity storage stands in as a plain
 * map. That is the store standing in for itself, the substitution the port's probes were
 * already allowed; the subject of every rule being compared is a class above, not the
 * store. The consequence is stated in the report -- these figures are the decision
 * procedure's cost and say nothing about what an entity round-trip costs.
 *
 * <p>Run with:
 * {@code mvn -q test-compile exec:java -Dexec.classpathScope=test
 * -Dexec.mainClass=io.akka.newapi.BenchRunner -Dexec.args="<workloads> <answers> <timings>"}
 */
public final class BenchRunner {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  // SPEC-001 §3 rule 8's default table, the same ranges application.conf ships.
  private static final List<StatusRange> RETRY_ON =
      List.of(
          StatusRange.parse("100-199"), StatusRange.parse("300-399"), StatusRange.parse("401-407"),
          StatusRange.parse("409-499"), StatusRange.parse("500-503"), StatusRange.parse("505-523"),
          StatusRange.parse("525-599"));
  private static final List<StatusRange> DISABLE_ON = List.of(new StatusRange(401, 401));

  /** One workload's mutable world: channel status, and the wallet. */
  private static final class World {
    final Map<Long, Boolean> enabled = new LinkedHashMap<>();
    final Map<Long, Boolean> autoBan = new LinkedHashMap<>();
    final Map<Long, Long> priority = new LinkedHashMap<>();
    final Map<Long, Integer> weight = new LinkedHashMap<>();
    AccountState account;
  }

  private static World seedWorld(JsonNode workload) {
    var world = new World();
    for (JsonNode c : workload.path("channels")) {
      long id = c.path("id").asLong();
      world.enabled.put(id, true);
      world.autoBan.put(id, c.path("autoBan").asBoolean(true));
      world.priority.put(id, c.path("priority").asLong());
      world.weight.put(id, c.path("weight").asInt());
    }
    world.account = new AccountState(workload.path("startingBalance").asLong());
    return world;
  }

  private static List<ChannelCandidate> candidates(World world) {
    return world.enabled.entrySet().stream()
        .filter(Map.Entry::getValue)
        .map(e -> new ChannelCandidate(e.getKey(), world.priority.get(e.getKey()), world.weight.get(e.getKey()),
            world.autoBan.get(e.getKey())))
        .collect(Collectors.toList());
  }

  private static String runRequest(JsonNode workload, JsonNode request, World world, Random draws) {
    int maxRetries = workload.path("maxRetries").asInt();
    boolean autoDisable = workload.path("autoDisableEnabled").asBoolean();
    double modelRatio = workload.path("modelRatio").asDouble();
    double completionRatio = workload.path("completionRatio").asDouble();
    double groupRatio = workload.path("groupRatio").asDouble();
    long minPreConsume = workload.path("minPreConsumeTokens").asLong();

    long preConsumed = QuotaMath.preConsume(request.path("promptTokens").asLong(),
        request.path("maxOutputTokens").asLong(), minPreConsume, modelRatio, groupRatio);

    var reserved = world.account.reserve(preConsumed);
    if (!reserved.reserved()) {
      return "attempts=0|channels=|status=402|stopped=INSUFFICIENT_QUOTA|balance=" + world.account.balance();
    }
    world.account = reserved.state();

    JsonNode attempts = request.path("attempts");
    var used = new ArrayList<String>();
    for (int retryIndex = 0; retryIndex <= maxRetries; retryIndex++) {
      var selection = ChannelSelector.select(candidates(world), retryIndex, draws.nextLong() >>> 1);
      if (selection.channelId().isEmpty()) {
        world.account = world.account.refund(preConsumed);
        return "attempts=" + used.size() + "|channels=" + String.join(",", used)
            + "|status=503|stopped=NO_CHANNEL_AVAILABLE|balance=" + world.account.balance();
      }
      long channelId = selection.channelId().get();
      used.add(Long.toString(channelId));

      JsonNode a = attempts.get(Math.min(used.size() - 1, attempts.size() - 1));
      int status = a.path("status").asInt();
      boolean channelError = a.path("channelError").asBoolean();

      if (status >= 200 && status < 300) {
        long actual = QuotaMath.settle(a.path("promptTokens").asLong(), a.path("completionTokens").asLong(),
            completionRatio, modelRatio, groupRatio);
        world.account = world.account.settle(actual - preConsumed);
        return "attempts=" + used.size() + "|channels=" + String.join(",", used)
            + "|status=" + status + "|stopped=SUCCESS|balance=" + world.account.balance();
      }

      if (FailureClassifier.qualifiesForAutoDisable(status, channelError, autoDisable,
          world.autoBan.get(channelId), DISABLE_ON)) {
        world.enabled.put(channelId, false);
      }

      if (!FailureClassifier.isRetryable(status, channelError, RETRY_ON) || retryIndex == maxRetries) {
        world.account = world.account.refund(preConsumed);
        return "attempts=" + used.size() + "|channels=" + String.join(",", used)
            + "|status=" + status + "|stopped=ATTEMPTS_EXHAUSTED|balance=" + world.account.balance();
      }
    }
    throw new IllegalStateException("the loop above always returns before falling off its bound");
  }

  private static List<String> runWorkload(JsonNode workload, long seed) {
    var world = seedWorld(workload);
    var draws = new Random(seed);
    var answers = new ArrayList<String>();
    for (JsonNode request : workload.path("sequence")) {
      answers.add(runRequest(workload, request, world, draws));
    }
    return answers;
  }

  /**
   * A window aims for tens of milliseconds and the figure is its total divided by what it
   * held. No minimum over many short windows: a window that provably did the work can
   * still read zero off the platform clock, and a minimum picks exactly that reading.
   */
  private static ObjectNode time(JsonNode workload) {
    for (int perWindow = 1; perWindow <= (1 << 22); perWindow *= 2) {
      long start = System.nanoTime();
      for (int i = 0; i < perWindow; i++) {
        runWorkload(workload, 1L);
      }
      long elapsed = System.nanoTime() - start;
      if (elapsed >= 30_000_000L) {
        var out = MAPPER.createObjectNode();
        out.put("repetitions", perWindow);
        out.put("windowNanos", elapsed);
        out.put("nanosPerRun", elapsed / (double) perWindow);
        return out;
      }
    }
    throw new IllegalStateException("the pilot never measured anything for " + workload.path("id").asText());
  }

  private static ObjectNode distributionOf(JsonNode d) {
    var candidates = new ArrayList<ChannelCandidate>();
    for (JsonNode c : d.path("channels")) {
      candidates.add(new ChannelCandidate(c.path("id").asLong(), c.path("priority").asLong(),
          c.path("weight").asInt(), true));
    }
    var counts = new LinkedHashMap<Long, Integer>();
    var draws = new Random(1L);
    for (int i = 0; i < d.path("draws").asInt(); i++) {
      long id = ChannelSelector.select(candidates, 0, draws.nextLong() >>> 1).channelId().orElseThrow();
      counts.merge(id, 1, Integer::sum);
    }
    var out = MAPPER.createObjectNode();
    counts.forEach((id, n) -> out.put(Long.toString(id), n));
    return out;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 3) {
      System.err.println("usage: BenchRunner <workloads.json> <answers.json> <timings.json>");
      System.exit(2);
    }
    JsonNode suite = MAPPER.readTree(Files.readString(Path.of(args[0])));

    var answers = MAPPER.createObjectNode();
    var timings = MAPPER.createObjectNode();
    var distribution = MAPPER.createObjectNode();
    for (JsonNode workload : suite) {
      // The one entry with no sequence is the distribution: rule 3 is about how often
      // each candidate is picked, which no single answer can carry.
      if (!workload.path("sequence").elements().hasNext()) {
        distribution = distributionOf(workload);
        continue;
      }
      String name = workload.path("name").asText();
      var rows = MAPPER.createArrayNode();
      for (String outcome : runWorkload(workload, 1L)) {
        rows.add(MAPPER.createObjectNode().put("outcome", outcome));
      }
      answers.set(name, rows);
      timings.set(name, time(workload));
    }

    var root = MAPPER.createObjectNode();
    root.set("answers", answers);
    root.set("distribution", distribution);
    Files.writeString(Path.of(args[1]), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    var timingRoot = MAPPER.createObjectNode();
    timingRoot.set("timing", timings);
    Files.writeString(Path.of(args[2]), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(timingRoot));
    System.out.println("wrote " + args[1] + " and " + args[2]);
  }
}
