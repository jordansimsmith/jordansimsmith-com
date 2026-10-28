package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateListingImageResponse(@JsonProperty("image_url") String imageUrl) {}
