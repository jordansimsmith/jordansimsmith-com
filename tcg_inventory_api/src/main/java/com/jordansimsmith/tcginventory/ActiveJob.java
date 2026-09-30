package com.jordansimsmith.tcginventory;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;

public class ActiveJob {
  private final DynamoDbTable<JobItem> jobTable;

  public ActiveJob(DynamoDbTable<JobItem> jobTable) {
    this.jobTable = jobTable;
  }

  public boolean exists(String user) {
    return exists(user, null);
  }

  public boolean exists(String user, String jobType) {
    var request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortBeginsWith(
                    Key.builder()
                        .partitionValue(JobItem.formatPk(user))
                        .sortValue(JobItem.JOB_PREFIX)
                        .build()))
            .consistentRead(true)
            .build();

    for (var page : jobTable.query(request)) {
      for (var job : page.items()) {
        // only queued and running jobs can conflict with new work.
        if (("queued".equals(job.getStatus()) || "running".equals(job.getStatus()))
            && (jobType == null || jobType.equals(job.getJobType()))) {
          return true;
        }
      }
    }
    return false;
  }
}
