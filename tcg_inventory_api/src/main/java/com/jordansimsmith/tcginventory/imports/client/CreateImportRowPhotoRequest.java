package com.jordansimsmith.tcginventory.imports.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record CreateImportRowPhotoRequest(
    @JsonProperty("import_id") String importId,
    @JsonProperty("position") int position,
    @JsonProperty("photo_bytes") byte[] photoBytes) {}
