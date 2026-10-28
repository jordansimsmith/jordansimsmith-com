package com.jordansimsmith.tcginventory.games.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record FindGamesResponse(@JsonProperty("games") List<Game> games) {}
