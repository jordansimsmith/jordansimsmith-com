package com.jordansimsmith.tcginventory.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CatalogRefreshMessage(
    @JsonProperty("game") String game, @JsonProperty("scheduled_at") String scheduledAt) {}
