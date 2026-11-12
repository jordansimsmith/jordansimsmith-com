package com.jordansimsmith.tcginventory.imports.server;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ImportMessage(
    @JsonProperty("user") String user,
    @JsonProperty("import_id") String importId,
    @JsonProperty("type") ImportMessageType type) {}
