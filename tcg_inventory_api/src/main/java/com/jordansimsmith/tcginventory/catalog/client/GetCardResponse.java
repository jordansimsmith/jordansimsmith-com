package com.jordansimsmith.tcginventory.catalog.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GetCardResponse(@JsonProperty("card") Card card) {}
