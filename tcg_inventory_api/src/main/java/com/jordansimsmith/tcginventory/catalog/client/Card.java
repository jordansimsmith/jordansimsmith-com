package com.jordansimsmith.tcginventory.catalog.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record Card(
    @JsonProperty("game") String game,
    @JsonProperty("external_id") String externalId,
    @JsonProperty("name") String name,
    @JsonProperty("set_code") String setCode,
    @JsonProperty("set_name") String setName,
    @JsonProperty("collector_number") String collectorNumber,
    @JsonProperty("image_urls") ImageUrls imageUrls,
    @JsonProperty("available_finishes") List<String> availableFinishes) {}
