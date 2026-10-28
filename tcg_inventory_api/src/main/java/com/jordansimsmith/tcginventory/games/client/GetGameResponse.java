package com.jordansimsmith.tcginventory.games.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GetGameResponse(@JsonProperty("game") Game game) {}
