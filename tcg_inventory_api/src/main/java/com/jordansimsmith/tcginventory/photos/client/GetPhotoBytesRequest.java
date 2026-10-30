package com.jordansimsmith.tcginventory.photos.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GetPhotoBytesRequest(@JsonProperty("photo_id") String photoId) {}
