package com.faforever.server.integration.v2.client;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Message sent from the client to the server to request players to close the connection to another player.
 */
@Getter
@AllArgsConstructor
class DisconnectPeerClientMessage extends V2ClientMessage {
  /** The ID of the player who should be disconnected. */
  private int playerId;
}
