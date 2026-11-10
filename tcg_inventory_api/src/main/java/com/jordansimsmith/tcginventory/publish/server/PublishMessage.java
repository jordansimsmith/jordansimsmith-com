package com.jordansimsmith.tcginventory.publish.server;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PublishMessage(
    @JsonProperty("user") String user, @JsonProperty("publish_id") String publishId) {}
