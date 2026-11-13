package com.jordansimsmith.tcginventory.scans.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

public record CreateScanRequest(
    @JsonProperty("game") @Nullable String game,
    @JsonProperty("condition") @Nullable String condition,
    @JsonProperty("finish") @Nullable String finish,
    @JsonProperty("files") @Nullable List<ScanFile> files) {}
