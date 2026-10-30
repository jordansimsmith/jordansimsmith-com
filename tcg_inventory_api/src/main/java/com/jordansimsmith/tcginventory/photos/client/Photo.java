package com.jordansimsmith.tcginventory.photos.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Photo(@JsonProperty("photo_id") String photoId, @JsonProperty("url") String url) {}
