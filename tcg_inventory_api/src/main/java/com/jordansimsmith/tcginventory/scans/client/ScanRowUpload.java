package com.jordansimsmith.tcginventory.scans.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import javax.annotation.Nullable;

public record ScanRowUpload(
    @JsonProperty("scan_position") int scanPosition,
    @JsonProperty("filename") String filename,
    @JsonProperty("size_bytes") long sizeBytes,
    @JsonProperty("uploaded") boolean uploaded,
    @JsonProperty("upload_url") @Nullable String uploadUrl,
    @JsonProperty("upload_headers") @Nullable Map<String, String> uploadHeaders) {}
