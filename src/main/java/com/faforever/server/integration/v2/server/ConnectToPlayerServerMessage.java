package com.faforever.server.integration.v2.server;

import lombok.Getter;
import lombok.Setter;

/**
 * Message sent from the server to the client containing an info message to be displayed to the user.
 */
@Getter
@Setter
class ConnectToPlayerServerMessage extends V2ServerMessage {
  /** The name of the player to connect to. */
  String playerName;
  /** The ID of the player to connect to. */
  int playerId;
}