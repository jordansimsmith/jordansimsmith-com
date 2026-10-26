package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record FindCardListingsRequest(
    @JsonProperty("card_id") String cardId,
    @JsonProperty("limit") @Nullable Integer limit,
    @JsonProperty("continuation") @Nullable String continuation) {}
