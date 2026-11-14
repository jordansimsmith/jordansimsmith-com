package com.jordansimsmith.tcginventory.catalog.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

public record SearchCardsResponse(
    @JsonProperty("cards") List<Card> cards,
    @JsonProperty("next_continuation") @Nullable String nextContinuation) {}
