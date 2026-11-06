package com.jordansimsmith.tcginventory.orders.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record OrderLine(
    @JsonProperty("game") String game,
    @JsonProperty("name") String name,
    @JsonProperty("set_code") String setCode,
    @JsonProperty("collector_number") String collectorNumber,
    @JsonProperty("finish") String finish,
    @JsonProperty("condition") String condition,
    @JsonProperty("quantity") int quantity,
    @JsonProperty("price") @Nullable String price,
    @JsonProperty("listed_price") @Nullable String listedPrice) {}
