package com.jordansimsmith.tcginventory;

import javax.annotation.Nullable;

public interface JobProcessor {
  JobResult processBatch(String user, JobItem jobItem);

  sealed interface JobResult permits SuccessJobResult, FailureJobResult {}

  record SuccessJobResult(int processedCount, @Nullable String continuation) implements JobResult {}

  record FailureJobResult(String error) implements JobResult {}
}
