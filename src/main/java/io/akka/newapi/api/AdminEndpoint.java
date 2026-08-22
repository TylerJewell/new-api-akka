package io.akka.newapi.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import io.akka.newapi.application.AbilityIndexEntity;
import io.akka.newapi.application.AccountEntity;
import io.akka.newapi.application.ChannelEntity;
import io.akka.newapi.domain.AccountState;
import io.akka.newapi.domain.ChannelState;
import java.util.Set;

/**
 * Registers channels and ability-index entries, and funds accounts -- the operator
 * surface this port needs to be usable at all, since SPEC-001 has no source-equivalent
 * admin UI in scope (gui/manifest.json).
 *
 * <p>Not for the gateway's callers: this is configuration and funding, the way the
 * source's own admin controllers (`controller/channel.go`) sit apart from the relay path.
 */
@Acl(allow = @Acl.Matcher(service = "*"))
@HttpEndpoint("/admin")
public class AdminEndpoint extends AbstractHttpEndpoint {

  private final ComponentClient componentClient;

  public AdminEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  public record RegisterChannelRequest(
      long id, Set<String> groups, Set<String> models, long priority, int weight, boolean autoBan) {}

  @Post("/channels")
  public ChannelState registerChannel(RegisterChannelRequest request) {
    componentClient
        .forKeyValueEntity(Long.toString(request.id()))
        .method(ChannelEntity::register)
        .invoke(new ChannelEntity.Register(request.groups(), request.models(), request.priority(), request.weight(), request.autoBan()));
    for (String group : request.groups()) {
      for (String model : request.models()) {
        componentClient
            .forKeyValueEntity(group + "::" + model)
            .method(AbilityIndexEntity::addChannel)
            .invoke(new AbilityIndexEntity.ChannelId(request.id()));
      }
    }
    return componentClient.forKeyValueEntity(Long.toString(request.id())).method(ChannelEntity::get).invoke();
  }

  @Get("/channels/{id}")
  public ChannelState getChannel(long id) {
    return componentClient.forKeyValueEntity(Long.toString(id)).method(ChannelEntity::get).invoke();
  }

  @Post("/channels/{id}/enable")
  public ChannelState enableChannel(long id) {
    componentClient.forKeyValueEntity(Long.toString(id)).method(ChannelEntity::enable).invoke();
    return componentClient.forKeyValueEntity(Long.toString(id)).method(ChannelEntity::get).invoke();
  }

  public record DepositRequest(long amount) {}

  @Post("/accounts/{id}/deposit")
  public AccountState deposit(String id, DepositRequest request) {
    return componentClient
        .forKeyValueEntity(id)
        .method(AccountEntity::deposit)
        .invoke(new AccountEntity.Deposit(request.amount()));
  }

  @Get("/accounts/{id}")
  public AccountState getAccount(String id) {
    return componentClient.forKeyValueEntity(id).method(AccountEntity::get).invoke();
  }
}
