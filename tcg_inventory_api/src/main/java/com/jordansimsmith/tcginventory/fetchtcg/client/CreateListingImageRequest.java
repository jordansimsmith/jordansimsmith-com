package com.jordansimsmith.tcginventory.fetchtcg.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateListingImageRequest(
    @JsonProperty("image_bytes") byte[] imageBytes, @JsonProperty("filename") String filename) {}
