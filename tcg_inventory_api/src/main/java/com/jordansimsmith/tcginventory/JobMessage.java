package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.annotation.JsonProperty;

public record JobMessage(
    @JsonProperty("user") String user,
    @JsonProperty("job_id") String jobId,
    @JsonProperty("job_type") String jobType) {

  public String deduplicationId(int continuation) {
    return jobId + "#" + continuation;
  }
}
