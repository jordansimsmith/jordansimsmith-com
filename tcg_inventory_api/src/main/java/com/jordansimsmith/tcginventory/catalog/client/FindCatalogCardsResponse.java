package com.jordansimsmith.tcginventory.catalog.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record FindCatalogCardsResponse(@JsonProperty("cards") List<CatalogPrinting> cards) {}
