package com.jordansimsmith.tcginventory.catalog.server;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CatalogRefreshMessage(
    @JsonProperty("game") String game, @JsonProperty("scheduled_at") String scheduledAt) {}
