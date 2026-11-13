package com.jordansimsmith.tcginventory.scans.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.scans.client.DeleteScanRequest;
import com.jordansimsmith.tcginventory.scans.client.DeleteScanResponse;
import java.util.List;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;

public class DeleteScanService {
  private final ScanReadRepository scanReadRepository;
  private final ScanMutationRepository scanMutationRepository;
  private final S3Client s3Client;
  private final String bucket;

  public DeleteScanService(
      ScanReadRepository scanReadRepository,
      ScanMutationRepository scanMutationRepository,
      S3Client s3Client,
      String bucket) {
    this.scanReadRepository = scanReadRepository;
    this.scanMutationRepository = scanMutationRepository;
    this.s3Client = s3Client;
    this.bucket = bucket;
  }

  public DeleteScanResponse deleteScan(RequestContext ctx, DeleteScanRequest request)
      throws ServiceException {
    if (request.scanId() == null || request.scanId().isBlank()) {
      throw new BadRequestException("scan_id is required");
    }
    var user = ctx.user();
    var scan = scanReadRepository.getScan(user, request.scanId());
    if (scan == null) {
      throw new NotFoundException("Not Found");
    }
    if (!"reviewing".equals(scan.getStatus())) {
      throw new ConflictException("scan is not in a deletable status");
    }
    var rows = scanReadRepository.findScanRows(user, request.scanId());
    scanMutationRepository.deleteScan(user, request.scanId());
    for (var row : rows) {
      scanMutationRepository.deleteScanRow(row);
    }
    deleteSourceObjects(rows);
    return new DeleteScanResponse();
  }

  private void deleteSourceObjects(List<ScanRowItem> rows) {
    if (rows.isEmpty()) {
      return;
    }
    var objects =
        rows.stream().map(row -> ObjectIdentifier.builder().key(row.getS3Key()).build()).toList();
    var response =
        s3Client.deleteObjects(
            DeleteObjectsRequest.builder()
                .bucket(bucket)
                .delete(Delete.builder().objects(objects).quiet(true).build())
                .build());
    if (!response.errors().isEmpty()) {
      throw new IllegalStateException("failed to delete one or more scan source objects");
    }
  }
}
