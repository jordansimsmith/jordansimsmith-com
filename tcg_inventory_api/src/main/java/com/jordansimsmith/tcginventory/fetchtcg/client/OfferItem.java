package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import javax.annotation.Nullable;

public record OfferItem(
    @JsonProperty("listing") @Nullable OfferListing listing,
    @JsonProperty("quantity") int quantity,
    @JsonProperty("price") @Nullable BigDecimal price) {}
