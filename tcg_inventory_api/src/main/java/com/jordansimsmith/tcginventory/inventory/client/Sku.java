package com.jordansimsmith.tcginventory.inventory.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import javax.annotation.Nullable;

public record Sku(
    @JsonProperty("sku_id") String skuId,
    @JsonProperty("game") String game,
    @JsonProperty("external_id") String externalId,
    @JsonProperty("name") String name,
    @JsonProperty("set_code") String setCode,
    @JsonProperty("set_name") String setName,
    @JsonProperty("collector_number") String collectorNumber,
    @JsonProperty("finish") String finish,
    @JsonProperty("condition") String condition,
    @JsonProperty("last_published_price") @Nullable BigDecimal lastPublishedPrice) {}
