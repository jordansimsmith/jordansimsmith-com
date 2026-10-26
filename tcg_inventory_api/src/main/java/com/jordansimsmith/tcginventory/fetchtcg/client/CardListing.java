package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record CardListing(
    @JsonProperty("id") int id,
    @JsonProperty("condition") String condition,
    @JsonProperty("listed_price") BigDecimal listedPrice,
    @JsonProperty("seller_username") String sellerUsername) {}
