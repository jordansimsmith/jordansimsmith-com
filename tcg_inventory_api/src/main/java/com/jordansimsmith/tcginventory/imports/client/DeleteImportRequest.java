package com.jordansimsmith.tcginventory.imports.client;

import com.fasterxml.jackson.annotation.JsonProperty;

public record DeleteImportRequest(@JsonProperty("import_id") String importId) {}
