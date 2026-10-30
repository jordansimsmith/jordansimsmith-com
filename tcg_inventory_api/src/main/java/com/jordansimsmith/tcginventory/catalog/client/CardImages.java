package com.jordansimsmith.tcginventory.catalog.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CardImages(
    @JsonProperty("external_id") String externalId,
    @JsonProperty("image_urls") ImageUrls imageUrls) {}
