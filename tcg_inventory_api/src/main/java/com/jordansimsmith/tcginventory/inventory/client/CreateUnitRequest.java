package com.jordansimsmith.tcginventory.inventory.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import javax.annotation.Nullable;

public record CreateUnitRequest(
    @JsonProperty("operation_id") String operationId,
    @JsonProperty("game") String game,
    @JsonProperty("external_id") String externalId,
    @JsonProperty("finish") String finish,
    @JsonProperty("condition") Condition condition,
    @JsonProperty("name") String name,
    @JsonProperty("set_code") String setCode,
    @JsonProperty("set_name") String setName,
    @JsonProperty("collector_number") String collectorNumber,
    @JsonProperty("price") @Nullable BigDecimal price,
    @JsonProperty("fetchtcg_card_id") @Nullable String fetchtcgCardId,
    @JsonProperty("fetchtcg_set_id") int fetchtcgSetId,
    @JsonProperty("photos") List<String> photos) {}
