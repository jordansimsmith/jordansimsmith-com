package com.jordansimsmith.tcginventory.imports.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.jordansimsmith.tcginventory.inventory.client.Condition;
import javax.annotation.Nullable;

public record UpdateImportRowRequest(
    @JsonProperty("import_id") String importId,
    @JsonProperty("position") int position,
    @JsonProperty("condition") @Nullable Condition condition) {}
