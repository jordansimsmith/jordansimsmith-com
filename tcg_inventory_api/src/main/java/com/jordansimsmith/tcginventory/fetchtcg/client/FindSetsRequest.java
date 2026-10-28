package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FindSetsRequest(
    @JsonProperty("game_id") String gameId,
    @JsonProperty("external_set_id") String externalSetId) {}
