package com.jordansimsmith.tcginventory.scans.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;

public record ScanFile(
    @JsonProperty("filename") @Nullable String filename,
    @JsonProperty("size_bytes") @Nullable Long sizeBytes) {}
