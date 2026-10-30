package com.jordansimsmith.tcginventory.photos.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record FindPhotosRequest(@JsonProperty("photo_ids") List<String> photoIds) {}
