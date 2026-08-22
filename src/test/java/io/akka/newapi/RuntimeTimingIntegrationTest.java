package io.akka.newapi;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.newapi.application.AbilityIndexEntity;
import io.akka.newapi.application.AccountEntity;
import io.akka.newapi.application.ChannelEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Times a two-tier failover request end to end with the Akka runtime running: the HTTP
 * endpoint, three entity reads, a reserve, a settle, and the scripted upstream in between.
 *
 * <p>{@code BenchRunner} times the decision procedure with the entity store standing in as
 * a plain map, which answers what the rules cost and nothing about what holding them
 * durably costs. The source side of the benchmark runs against real SQLite, so a ratio
 * against the map figure compares a database to a hash map. This figure is the one that
 * can be set beside the source's: both sides doing their own storage.
 *
 * <p>Written to {@code new-api-port/bench/port-runtime-timings.json} so the report quotes
 * a file a rerun overwrites, rather than a number transcribed by hand.
 */
public class RuntimeTimingIntegrationTest extends TestKitSupport {

  private static final ScriptedUpstream UPSTREAM = new ScriptedUpstream();
  private static final Path OUTPUT = Path.of("..", "new-api-port", "bench", "port-runtime-timings.json");

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT
        .withAdditionalConfig(
            """
            new-api.dispatch.max-retries = 2
            new-api.pricing.min-pre-consume-tokens = 500
            new-api.auto-disable.enabled = false
            """)
        .withMockedHttpService("upstream", UPSTREAM::answer);
  }

  private void registerChannel(long id, long priority) {
    componentClient
        .forKeyValueEntity(Long.toString(id))
        .method(ChannelEntity::register)
        .invoke(new ChannelEntity.Register(Set.of("bench"), Set.of("gpt-4"), priority, 0, true));
    componentClient
        .forKeyValueEntity("bench::gpt-4")
        .method(AbilityIndexEntity::addChannel)
        .invoke(new AbilityIndexEntity.ChannelId(id));
  }

  private int oneRequest() {
    var body = "{\"account_id\":\"timing\",\"group\":\"bench\",\"model\":\"gpt-4\",\"prompt_tokens\":100}";
    return httpClient
        .POST("/chat/completions")
        .withRequestBody(akka.http.javadsl.model.ContentTypes.APPLICATION_JSON, body.getBytes(StandardCharsets.UTF_8))
        .parseResponseBody(bytes -> new String(bytes, StandardCharsets.UTF_8))
        .invoke()
        .status()
        .intValue();
  }

  @Test
  public void timesTheTierFailoverWorkloadThroughTheRunningRuntime() throws Exception {
    registerChannel(1, 100);
    registerChannel(2, 50);
    componentClient
        .forKeyValueEntity("timing")
        .method(AccountEntity::deposit)
        .invoke(new AccountEntity.Deposit(1_000_000_000L));

    // The same shape as the tier-failover workload: the top tier fails, the next succeeds.
    UPSTREAM.reset();
    UPSTREAM.answersWith(500, 200);
    assertThat(oneRequest()).isEqualTo(200);

    // Warm the runtime before the measured window, so the figure is steady-state cost
    // rather than first-call class loading and connection setup.
    for (int i = 0; i < 50; i++) {
      UPSTREAM.reset();
      UPSTREAM.answersWith(500, 200);
      oneRequest();
    }

    int repetitions = 200;
    long start = System.nanoTime();
    for (int i = 0; i < repetitions; i++) {
      UPSTREAM.reset();
      UPSTREAM.answersWith(500, 200);
      oneRequest();
    }
    long windowNanos = System.nanoTime() - start;
    assertThat(windowNanos).isGreaterThan(1_000_000L);

    var mapper = new ObjectMapper();
    var row = mapper.createObjectNode();
    row.put("repetitions", repetitions);
    row.put("windowNanos", windowNanos);
    row.put("nanosPerRun", windowNanos / (double) repetitions);
    var timing = mapper.createObjectNode();
    timing.set("tier-failover-one-request", row);
    var root = mapper.createObjectNode();
    root.set("timing", timing);
    root.put(
        "whatThisIncludes",
        "One request through the HTTP endpoint with the runtime running: two selection "
            + "rounds, each reading the ability index and every candidate channel entity, "
            + "a reserve and a settle against the account entity, and two calls to a "
            + "scripted upstream. It does not include the upstream's own latency, which is "
            + "scripted to answer immediately on both sides of the benchmark.");
    Files.createDirectories(OUTPUT.getParent());
    Files.writeString(OUTPUT, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
  }
}
