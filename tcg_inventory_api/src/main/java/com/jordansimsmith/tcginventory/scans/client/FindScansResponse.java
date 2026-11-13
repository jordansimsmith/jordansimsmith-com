package com.jordansimsmith.tcginventory.scans.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

public record FindScansResponse(
    @JsonProperty("scans") List<ScanSummary> scans,
    @JsonProperty("next_continuation") @Nullable String nextContinuation) {}
