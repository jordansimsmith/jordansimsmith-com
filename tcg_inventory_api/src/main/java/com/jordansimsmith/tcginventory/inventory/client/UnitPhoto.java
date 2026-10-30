package com.jordansimsmith.tcginventory.inventory.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UnitPhoto(
    @JsonProperty("photo_id") String photoId, @JsonProperty("url") String url) {}
