package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

public record FindCardsResponse(
    @JsonProperty("cards") List<CardSearchResult> cards,
    @JsonProperty("next_continuation") @Nullable String nextContinuation) {}
