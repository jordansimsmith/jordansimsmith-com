package com.jordansimsmith.tcginventory.imports.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

public record FindImportsResponse(
    @JsonProperty("imports") List<ImportSummary> imports,
    @JsonProperty("next_continuation") @Nullable String nextContinuation) {}
