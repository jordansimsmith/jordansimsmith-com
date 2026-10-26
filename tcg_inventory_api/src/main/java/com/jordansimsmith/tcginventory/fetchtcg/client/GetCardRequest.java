package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GetCardRequest(@JsonProperty("card_id") String cardId) {}
