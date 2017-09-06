package com.faforever.server.client;

import com.faforever.server.FafServerApplication.ApplicationShutdownEvent;
import com.faforever.server.api.dto.UpdatedAchievementResponse;
import com.faforever.server.chat.JoinChatChannelResponse;
import com.faforever.server.common.ServerMessage;
import com.faforever.server.config.ServerProperties;
import com.faforever.server.coop.CoopMissionResponse;
import com.faforever.server.coop.CoopService;
import com.faforever.server.entity.AvatarAssociation;
import com.faforever.server.entity.Clan;
import com.faforever.server.entity.FeaturedMod;
import com.faforever.server.entity.Game;
import com.faforever.server.entity.GlobalRating;
import com.faforever.server.entity.Player;
import com.faforever.server.game.DelayedResponse;
import com.faforever.server.game.GameResponse;
import com.faforever.server.game.HostGameResponse;
import com.faforever.server.game.StartGameProcessResponse;
import com.faforever.server.ice.ForwardedIceMessage;
import com.faforever.server.ice.IceServerList;
import com.faforever.server.integration.ClientGateway;
import com.faforever.server.matchmaker.MatchMakerResponse;
import com.faforever.server.mod.FeaturedModResponse;
import com.faforever.server.player.LoginDetailsResponse;
import com.faforever.server.player.PlayerInformationResponse;
import com.faforever.server.player.PlayerInformationResponse.Player.Avatar;
import com.faforever.server.player.PlayerInformationResponse.Player.Rating;
import com.faforever.server.social.SocialRelationListResponse;
import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service to send messages to the client.
 */
@Service
@Slf4j
public class ClientService {

  private final ClientGateway clientGateway;
  private final CoopService coopService;
  private final ConcurrentMap<Object, DelayedResponse> dirtyObjects;
  private final ServerProperties serverProperties;
  private final ExecutorService executorService;

  public ClientService(ClientGateway clientGateway, CoopService coopService, ServerProperties serverProperties) {
    this.clientGateway = clientGateway;
    this.coopService = coopService;
    this.serverProperties = serverProperties;
    dirtyObjects = new ConcurrentHashMap<>();

    AtomicInteger threadCount = new AtomicInteger();
    executorService = Executors.newFixedThreadPool(1, runnable -> new Thread(runnable, "client-service-" + threadCount.incrementAndGet()));
  }

  public void startGameProcess(Game game, Player player) {
    log.debug("Telling '{}' to start game process for game '{}'", game.getHost(), game);
    send(new StartGameProcessResponse(game.getFeaturedMod().getTechnicalName(), game.getId(), getCommandLineArgs(player)), player);
  }

  /**
   * Tells the client to connect to a host. The game process must have been started before.
   */
  public void connectToHost(Game game, @NotNull Player player) {
    log.debug("Telling '{}' to connect to '{}'", player, game.getHost());
    send(new ConnectToHostResponse(game.getHost().getId()), player);
  }

  /**
   * Tells the client to connect to another player. The game process must have been started before.
   *
   * @param player the player to send the message to
   * @param otherPlayer the player to connect to
   */
  public void connectToPlayer(Player player, Player otherPlayer) {
    log.debug("Telling '{}' to connect to '{}'", player, otherPlayer);
    send(new ConnectToPlayerResponse(otherPlayer.getLogin(), otherPlayer.getId()), player);
  }

  public void hostGame(Game game, @NotNull ConnectionAware connectionAware) {
    send(new HostGameResponse(game.getMapName()), connectionAware);
  }

  public void reportUpdatedAchievements(List<UpdatedAchievementResponse> playerAchievements, @NotNull ConnectionAware connectionAware) {
    send(new UpdatedAchievementsResponse(playerAchievements.stream()
        .map(item -> new UpdatedAchievementsResponse.UpdatedAchievement(
          item.getAchievementId(),
          item.getCurrentSteps(),
          item.getState(),
          item.isNewlyUnlocked()
        ))
        .collect(Collectors.toList())),
      connectionAware);
  }

  /**
   * Send a player his own information, usually called after successful login.
   */
  public void sendLoginDetails(Player player, @NotNull ConnectionAware connectionAware) {
    send(new LoginDetailsResponse(toPlayerInformationResponse(player)), connectionAware);
  }

  /**
   * @deprecated the client should fetch featured mods from the API.
   */
  @Deprecated
  public void sendModList(List<FeaturedMod> modList, @NotNull ConnectionAware connectionAware) {
    modList.forEach(mod -> send(new FeaturedModResponse(
      mod.getTechnicalName(), mod.getDisplayName(), mod.getDescription(), mod.getDisplayOrder()
    ), connectionAware));
  }

  public void sendGameList(Collection<GameResponse> games, ConnectionAware connectionAware) {
    games.forEach(game -> send(game, connectionAware));
  }

  /**
   * Sends a response that needs to be send to the client at some point in the future. Responses can be hold back for a
   * while in order to avoid message flooding if the object is updated frequently in a short amount of time.
   *
   * @param object the object to be sent.
   * @param minDelay the minimum time to wait since the object has been updated.
   * @param maxDelay the maximum time to wait before the object is forcibly sent, even if the object has been updated
   * less than {@code minDelay} ago. This helps to avoid objects being delayed for too long if they receive frequent
   * updates.
   * @param idFunction the function to use to calculate the object's ID, so that subsequent calls can be associated with
   * previous submissions of the same object. Special care needs to be taken that the generated ID does not clash with
   * IDs generated by other callers, so it's advised to add a prefix like 'game-1' instead of '1'.
   * @param <T> the type of the submitted object
   */
  @SuppressWarnings("unchecked")
  public <T extends ServerMessage> void broadcastDelayed(T object, Duration minDelay, Duration maxDelay, Function<T, Object> idFunction) {
    log.trace("Received object to send delayed: {}", object);
    dirtyObjects.computeIfAbsent(idFunction.apply(object), o -> new DelayedResponse<>(object, minDelay, maxDelay))
      .onUpdated(object);
  }

  /**
   * @deprecated the client should ask the API instead
   */
  @Deprecated
  public void sendCoopList(ClientConnection clientConnection) {
    coopService.getMaps().stream()
      .map(map -> new CoopMissionResponse(map.getName(), map.getDescription(), map.getFilename()))
      .forEach(coopMissionResponse -> clientGateway.send(coopMissionResponse, clientConnection));
  }

  /**
   * Tells the client to drop game connection to the player with the specified ID.
   */
  public void disconnectPlayerFromGame(int playerId, Collection<? extends ConnectionAware> receivers) {
    receivers.forEach(connectionAware ->
      clientGateway.send(new DisconnectPlayerFromGameResponse(playerId), connectionAware.getClientConnection()));
  }

  @Scheduled(fixedDelay = 200)
  public void broadcastDirtyObjects() {
    List<Object> objectIds = dirtyObjects.entrySet().stream()
      .filter(entry -> {
        DelayedResponse<?> delayedResponse = entry.getValue();
        Instant now = Instant.now();

        return now.isAfter(delayedResponse.getUpdateTime().plus(delayedResponse.getMinDelay()))
          || now.isAfter(delayedResponse.getCreateTime().plus(delayedResponse.getMaxDelay()));
      })
      .map(Map.Entry::getKey)
      .collect(Collectors.toList());

    if (objectIds.isEmpty()) {
      return;
    }

    log.trace("Sending '{}' delayed responses", objectIds.size());
    objectIds.forEach(id -> {
      DelayedResponse<?> delayedResponse = dirtyObjects.remove(id);
      clientGateway.broadcast(delayedResponse.getResponse());
    });
  }

  /**
   * Notifies the player about available opponents in the matchmaker.
   *
   * @param queueName name of the queue that has opponents available
   */
  public void sendMatchmakerNotification(String queueName, ConnectionAware recipient) {
    send(new MatchMakerResponse(queueName), recipient);
  }

  /**
   * Sends a list of player information to the specified recipient.
   */
  public void sendPlayerInformation(Collection<Player> players, ConnectionAware recipient) {
    toPlayerInformationResponses(players)
      .thenAccept(responses -> send(responses, recipient));
  }

  /**
   * Sends a list of player information to all authenticated clients.
   */
  public void broadcastPlayerInformation(Collection<Player> players) {
    toPlayerInformationResponses(players)
      .thenAccept(responses -> broadcastDelayed(responses, Duration.ofSeconds(2), Duration.ofSeconds(5), o -> "players"));
  }

  /**
   * Sends a list of chat channels to join to the client.
   */
  public void sendChatChannels(Set<String> channelNames, ConnectionAware recipient) {
    // TODO write test
    send(new JoinChatChannelResponse(channelNames), recipient);
  }

  /**
   * Sends a list of ICE servers to the client.
   */
  public void sendIceServers(List<IceServerList> iceServers, ConnectionAware recipient) {
    send(new IceServersResponse(iceServers), recipient);
  }

  public void sendIceMessage(int senderId, Object content, ConnectionAware recipient) {
    send(new ForwardedIceMessage(senderId, content), recipient);
  }

  public void sendSocialRelations(SocialRelationListResponse response, ConnectionAware recipient) {
    send(response, recipient);
  }

  private CompletableFuture<PlayerInformationResponses> toPlayerInformationResponses(Collection<Player> players) {
    return CompletableFuture.supplyAsync((() -> new PlayerInformationResponses(players.stream()
      .map(this::toPlayerInformationResponse)
      .collect(Collectors.toList()))), executorService);
  }

  private PlayerInformationResponse toPlayerInformationResponse(Player player) {
    Optional<Avatar> avatar = player.getAvailableAvatars().stream()
      .filter(AvatarAssociation::isSelected)
      .findFirst()
      .map(association -> {
        com.faforever.server.entity.Avatar avatarEntity = association.getAvatar();
        return new Avatar(avatarEntity.getUrl(), avatarEntity.getTooltip());
      });

    Optional<Rating> globalRating = Optional.ofNullable(player.getGlobalRating())
      .map(rating -> new Rating(rating.getMean(), rating.getDeviation()));
    Optional<Rating> ladder1v1Rating = Optional.ofNullable(player.getLadder1v1Rating())
      .map(rating -> new Rating(rating.getMean(), rating.getDeviation()));

    return new PlayerInformationResponse(
      player.getId(),
      player.getLogin(),
      player.getCountry(),
      new PlayerInformationResponse.Player(
        globalRating.orElse(null),
        ladder1v1Rating.orElse(null),
        Optional.ofNullable(player.getGlobalRating()).map(GlobalRating::getNumGames).orElse(0),
        avatar.orElse(null),
        Optional.ofNullable(player.getClan()).map(Clan::getTag).orElse(null)
      )
    );
  }

  @EventListener
  @VisibleForTesting
  void onServerShutdown(ApplicationShutdownEvent event) {
    try {
      clientGateway.broadcast(new InfoResponse(serverProperties.getShutdown().getMessage()));
    } catch (Exception e) {
      log.warn("Could not broadcast shutdown to clients.", e);
    }
  }

  /**
   * @deprecated passing command line args to the client is a bad (legacy) idea.
   */
  @Deprecated
  private List<String> getCommandLineArgs(Player player) {
    int numGames = Optional.ofNullable(player.getGlobalRating()).map(GlobalRating::getNumGames).orElse(0);
    return Arrays.asList("/numgames", String.valueOf(numGames));
  }

  private void send(ServerMessage serverMessage, @NotNull ConnectionAware connectionAware) {
    ClientConnection clientConnection = connectionAware.getClientConnection();
    try {
      if (clientConnection == null) {
        throw new IllegalStateException("No connection available: " + connectionAware);
      }
      clientGateway.send(serverMessage, clientConnection);
    } catch (Exception e) {
      log.warn("Could not send message to connection '" + clientConnection + "': " + serverMessage, e);
    }
  }
}
