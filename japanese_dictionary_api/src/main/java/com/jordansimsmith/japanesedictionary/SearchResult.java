package com.jordansimsmith.japanesedictionary;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import javax.annotation.Nullable;

public record SearchResult(
    @JsonProperty("sequence") long sequence,
    @JsonProperty("expression") String expression,
    @JsonProperty("reading") String reading,
    @JsonProperty("reading_romaji") String readingRomaji,
    @Nullable @JsonProperty("frequency_rank") Integer frequencyRank,
    @Nullable @JsonProperty("pitch") Integer pitch,
    @JsonProperty("glossary_raw") JsonNode glossaryRaw) {}
