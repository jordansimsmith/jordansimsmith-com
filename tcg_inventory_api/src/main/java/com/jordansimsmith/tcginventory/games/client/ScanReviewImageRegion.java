package com.jordansimsmith.tcginventory.games.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ScanReviewImageRegion(
    @JsonProperty("id") String id,
    @JsonProperty("display_name") String displayName,
    @JsonProperty("x") double x,
    @JsonProperty("y") double y,
    @JsonProperty("width") double width,
    @JsonProperty("height") double height) {}
