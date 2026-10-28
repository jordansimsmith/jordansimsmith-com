package com.jordansimsmith.tcginventory.games.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GetGameRequest(@JsonProperty("game_id") String gameId) {}
