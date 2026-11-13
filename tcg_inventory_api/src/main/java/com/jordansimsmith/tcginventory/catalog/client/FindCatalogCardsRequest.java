package com.jordansimsmith.tcginventory.catalog.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record FindCatalogCardsRequest(
    @JsonProperty("game") String game, @JsonProperty("external_ids") List<String> externalIds) {}
