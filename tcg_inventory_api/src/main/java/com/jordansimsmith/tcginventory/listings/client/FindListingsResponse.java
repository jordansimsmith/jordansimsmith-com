package com.jordansimsmith.tcginventory.listings.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record FindListingsResponse(@JsonProperty("listings") List<Listing> listings) {}
