package io.akka.newapi;

import static org.assertj.core.api.Assertions.assertThat;

import akka.http.javadsl.model.ContentTypes;
import akka.javasdk.testkit.TestKitSupport;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The operator surface names channels and accounts by id, which nobody outside the
 * deployment needs to see, so it is not on the same access footing as the gateway.
 */
public class AdminEndpointAccessIntegrationTest extends TestKitSupport {

  @Test
  public void theOperatorSurfaceIsNotReachableFromOutside() {
    assertThat(statusOf("/admin/channels/1")).isEqualTo(403);
    assertThat(statusOf("/admin/accounts/acct-x")).isEqualTo(403);
  }

  @Test
  public void theGatewayItselfIsReachableFromOutside() {
    var response =
        httpClient
            .POST("/chat/completions")
            .withRequestBody(ContentTypes.APPLICATION_JSON, "{}".getBytes(StandardCharsets.UTF_8))
            .parseResponseBody(b -> new String(b, StandardCharsets.UTF_8))
            .invoke();
    assertThat(response.status().intValue()).isNotEqualTo(403);
  }

  private int statusOf(String path) {
    return httpClient.GET(path).parseResponseBody(b -> new String(b, StandardCharsets.UTF_8)).invoke().status().intValue();
  }
}
