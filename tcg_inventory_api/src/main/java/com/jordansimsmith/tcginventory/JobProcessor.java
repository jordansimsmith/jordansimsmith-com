package com.jordansimsmith.tcginventory;

public interface JobProcessor {
  JobResult processBatch(String user, JobItem jobItem);

  sealed interface JobResult permits SuccessJobResult, FailureJobResult {}

  record SuccessJobResult(int processedUpTo, boolean complete) implements JobResult {}

  record FailureJobResult(String error) implements JobResult {}
}
