package com.jordansimsmith.tcginventory.games.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Finish(
    @JsonProperty("id") String id, @JsonProperty("display_name") String displayName) {}
