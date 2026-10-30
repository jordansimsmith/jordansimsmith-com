package com.jordansimsmith.tcginventory.photos.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreatePhotoRequest(@JsonProperty("photo_bytes") byte[] photoBytes) {}
