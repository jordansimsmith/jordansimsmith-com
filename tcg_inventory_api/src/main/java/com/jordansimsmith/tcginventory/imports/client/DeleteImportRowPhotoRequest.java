package com.jordansimsmith.tcginventory.imports.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DeleteImportRowPhotoRequest(
    @JsonProperty("import_id") String importId,
    @JsonProperty("position") int position,
    @JsonProperty("photo_id") String photoId) {}
