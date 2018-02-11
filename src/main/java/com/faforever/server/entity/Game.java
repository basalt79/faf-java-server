package com.faforever.server.entity;

import com.faforever.server.game.GameVisibility;
import com.faforever.server.stats.ArmyStatistics;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.MapKey;
import javax.persistence.OneToMany;
import javax.persistence.Table;
import javax.persistence.Transient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

@Entity
@Table(name = "game_stats")
@EqualsAndHashCode(of = "id")
@Setter
@Getter
@ToString(of = {"id", "title", "state"}, includeFieldNames = false)
public class Game {

  /**
   * A key-value map of gamespecific options, like {@code "PrebuiltUnits" -> "Off"}.
   */
  @Transient
  private final Map<String, Object> options;

  @Transient
  private final AtomicInteger desyncCounter;

  /**
   * Maps player IDs to a map of {@code armyId -> result} reported by this player.
   */
  @Transient
  private final Map<Integer, Map<Integer, ArmyResult>> reportedArmyResults;

  @Transient
  private final List<ArmyStatistics> armyStatistics;

  /**
   * Returns the players who are currently connected to the game (including observers), mapped by their player ID.
   */
  @Transient
  private final Map<Integer, Player> connectedPlayers;

  /**
   * Maps player IDs to key-value option maps, like {@code 1234 -> "Color" -> 1 }
   */
  @Transient
  private final Map<Integer, Map<String, Object>> playerOptions;

  /**
   * Set of player ids who accepted mutual draw
   */
  @Transient
  private final Set<Integer> mutuallyAcceptedDrawPlayerIds;

  /**
   * Maps AI names to key-value option maps, like {@code "Julian (AI: Rufus)" -> "Color" -> 1 }. Note that the game
   * doesn't always send the AI options with the same AI name. During lobby mode, it sends start spot, color etc. for
   * "AI: Easy" but when the game starts, it sends the Army ID as "Julian (AI: Easy)".
   */
  @Transient
  private final Map<String, Map<String, Object>> aiOptions;

  @Transient
  private final List<ModVersion> simMods;

  @Transient
  private Integer minRating;

  @Transient
  private Integer maxRating;

  /**
   * Due to "performance reasons" this ID isn't generated by the DB but by the software. I was <strong>not</strong> to
   * one who game up with this.
   */
  @Id
  @Column(name = "id")
  private Integer id;

  @Column(name = "startTime")
  private Instant startTime;

  @Column(name = "endTime")
  private Instant endTime;

  @Column(name = "gameType")
  private VictoryCondition victoryCondition;

  /**
   * Foreign key to "featured mod", but since there's no constraint in the database yet, hope for the best.
   */
  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "gameMod")
  private FeaturedMod featuredMod;

  @ManyToOne(optional = false, fetch = FetchType.LAZY)
  @JoinColumn(name = "host")
  private Player host;

  @ManyToOne(optional = false)
  @JoinColumn(name = "mapId")
  private MapVersion mapVersion;

  @Column(name = "gameName")
  private String title;

  @Column(name = "validity")
  @Enumerated(EnumType.ORDINAL)
  private Validity validity;

  /**
   * Mapped by player ID. Meant to be set just before game start.
   */
  @OneToMany(mappedBy = "game", cascade = CascadeType.ALL)
  @MapKey(name = "id")
  private Map<Integer, GamePlayerStats> playerStats;

  @Transient
  private GameState state;

  @Transient
  private String password;

  /**
   * Since some maps are unknown by the server (e.g. in-develop versions from map creators), the literal map name is
   * kept.
   */
  @Transient
  private String mapName;

  @Transient
  private int maxPlayers;

  @Transient
  private boolean ratingEnforced;

  @Transient
  private GameVisibility gameVisibility;

  @Transient
  private boolean mutuallyAgreedDraw;

  /**
   * Future that is completed as soon as the game is ready to be joined. A game may never complete this future, for
   * instance if the host's game crashes before entering lobby mode, so never wait without a timeout.
   */
  @Transient
  private CompletableFuture<Game> joinableFuture;

  public Game(int id) {
    this();
    this.id = id;
  }

  public Game() {
    state = GameState.INITIALIZING;
    playerOptions = new HashMap<>();
    options = new HashMap<>();
    aiOptions = new HashMap<>();
    reportedArmyResults = new HashMap<>();
    mutuallyAcceptedDrawPlayerIds = new HashSet<>();
    armyStatistics = new ArrayList<>();
    playerStats = new HashMap<>();
    simMods = new ArrayList<>();
    connectedPlayers = new HashMap<>();
    desyncCounter = new AtomicInteger();
    validity = Validity.VALID;
    gameVisibility = GameVisibility.PUBLIC;
    victoryCondition = VictoryCondition.DEMORALIZATION;
    joinableFuture = new CompletableFuture<>();
  }

  public void replaceArmyStatistics(List<ArmyStatistics> newList) {
    synchronized (armyStatistics) {
      armyStatistics.clear();
      armyStatistics.addAll(newList);
    }
  }

  /**
   * Returns an unmodifiable list of army statistics.
   */
  public List<ArmyStatistics> getArmyStatistics() {
    return Collections.unmodifiableList(armyStatistics);
  }

  public Game setState(GameState state) {
    GameState.verifyTransition(this.state, state);
    this.state = state;
    joinableFuture.complete(this);
    return this;
  }
}
