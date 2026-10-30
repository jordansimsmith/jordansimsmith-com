package com.jordansimsmith.tcginventory.catalog.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record FindCardImagesResponse(@JsonProperty("images") List<CardImages> images) {}
