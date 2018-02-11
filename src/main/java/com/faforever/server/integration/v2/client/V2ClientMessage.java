package com.faforever.server.integration.v2.client;

/**
 * Superclass of all message DTO classes sent from a client using the {@link com.faforever.server.integration.Protocol#V2_JSON_UTF_8}
 * protocol to the server. A client does not need to be a player's client but can also be another service like the
 * Galactic War Server or the API.
 */
abstract class V2ClientMessage {
}
