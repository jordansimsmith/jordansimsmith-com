package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record FindSetsResponse(@JsonProperty("sets") List<CardSet> sets) {}
