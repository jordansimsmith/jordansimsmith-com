package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;
import javax.annotation.Nullable;

public record UpdateListingRequest(
    @JsonProperty("card_id") String cardId,
    @JsonProperty("condition") String condition,
    @JsonProperty("quantity") int quantity,
    @JsonProperty("price") BigDecimal price,
    @JsonProperty("front_image") @Nullable String frontImage,
    @JsonProperty("additional_images") @Nullable List<String> additionalImages) {}
