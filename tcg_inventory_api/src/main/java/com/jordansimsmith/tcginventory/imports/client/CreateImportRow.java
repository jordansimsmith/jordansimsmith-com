package com.jordansimsmith.tcginventory.imports.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.jordansimsmith.tcginventory.inventory.client.Condition;

public record CreateImportRow(
    @JsonProperty("external_id") String externalId,
    @JsonProperty("name") String name,
    @JsonProperty("set_code") String setCode,
    @JsonProperty("set_name") String setName,
    @JsonProperty("collector_number") String collectorNumber,
    @JsonProperty("finish") String finish,
    @JsonProperty("condition") Condition condition,
    @JsonProperty("language") Language language) {}
