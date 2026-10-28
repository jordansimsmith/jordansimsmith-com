package com.jordansimsmith.tcginventory;

import com.jordansimsmith.service.ServiceException;
import javax.annotation.Nullable;

public interface JobProcessor {
  JobResult processBatch(String user, JobItem jobItem) throws ServiceException;

  sealed interface JobResult permits SuccessJobResult, FailureJobResult {}

  record SuccessJobResult(int processedCount, @Nullable String continuation) implements JobResult {}

  record FailureJobResult(String error) implements JobResult {}
}
