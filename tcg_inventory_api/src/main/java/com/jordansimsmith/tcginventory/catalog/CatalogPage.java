package com.jordansimsmith.tcginventory.catalog;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

public record CatalogPage(
    @JsonProperty("cards") List<CatalogCard> cards,
    @JsonProperty("next_continuation") @Nullable String nextContinuation) {}
