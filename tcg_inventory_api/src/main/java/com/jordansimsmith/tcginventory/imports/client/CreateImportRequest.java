package com.jordansimsmith.tcginventory.imports.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

public record CreateImportRequest(
    @JsonProperty("operation_id") String operationId,
    @JsonProperty("game") String game,
    @JsonProperty("csv") @Nullable String csv,
    @JsonProperty("filename") @Nullable String filename,
    @JsonProperty("rows") @Nullable List<CreateImportRow> rows) {}
