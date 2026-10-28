package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CardSearchResult(@JsonProperty("id") String id) {}
