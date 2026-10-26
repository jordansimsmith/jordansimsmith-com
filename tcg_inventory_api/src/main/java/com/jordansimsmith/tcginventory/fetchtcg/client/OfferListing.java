package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import javax.annotation.Nullable;

public record OfferListing(
    @JsonProperty("id") int id,
    @JsonProperty("condition") String condition,
    @JsonProperty("listed_price") @Nullable BigDecimal listedPrice) {}
