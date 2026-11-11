package com.jordansimsmith.tcginventory.imports.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import java.util.List;
import javax.annotation.Nullable;

public record ImportRow(
    @JsonProperty("position") int position,
    @JsonProperty("name") String name,
    @JsonProperty("set_code") String setCode,
    @JsonProperty("set_name") String setName,
    @JsonProperty("collector_number") String collectorNumber,
    @JsonProperty("finish") String finish,
    @JsonProperty("condition") Condition condition,
    @JsonProperty("external_id") String externalId,
    @JsonProperty("decision") @Nullable ImportDecision decision,
    @JsonProperty("decision_reason") @Nullable String decisionReason,
    @JsonProperty("market_price") @Nullable String marketPrice,
    @JsonProperty("suggested_price") @Nullable String suggestedPrice,
    @JsonProperty("photos") List<String> photos,
    @JsonProperty("needs_photos") boolean needsPhotos) {}
