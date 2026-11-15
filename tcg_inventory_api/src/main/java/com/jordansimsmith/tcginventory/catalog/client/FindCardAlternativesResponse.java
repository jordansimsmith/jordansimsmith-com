package com.jordansimsmith.tcginventory.catalog.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

public record FindCardAlternativesResponse(
    @JsonProperty("cards") List<Card> cards,
    @JsonProperty("next_continuation") @Nullable String nextContinuation) {}
