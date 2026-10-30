package com.jordansimsmith.tcginventory.photos.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record FindPhotosResponse(@JsonProperty("photos") List<Photo> photos) {}
