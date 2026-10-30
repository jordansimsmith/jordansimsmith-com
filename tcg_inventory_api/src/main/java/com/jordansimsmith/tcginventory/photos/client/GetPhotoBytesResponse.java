package com.jordansimsmith.tcginventory.photos.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GetPhotoBytesResponse(@JsonProperty("photo_bytes") byte[] photoBytes) {}
