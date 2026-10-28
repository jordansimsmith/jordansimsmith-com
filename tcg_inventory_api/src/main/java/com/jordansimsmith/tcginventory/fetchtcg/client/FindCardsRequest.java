package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record FindCardsRequest(
    @JsonProperty("game_id") String gameId,
    @JsonProperty("set_id") int setId,
    @JsonProperty("card_name") String cardName,
    @JsonProperty("finish") String finish,
    @JsonProperty("limit") @Nullable Integer limit,
    @JsonProperty("continuation") @Nullable String continuation) {}
