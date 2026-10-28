package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CardSet(@JsonProperty("id") int id, @JsonProperty("name") String name) {}
