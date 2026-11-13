package com.jordansimsmith.tcginventory.scans.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.scans.client.DeleteScanRowRequest;
import com.jordansimsmith.tcginventory.scans.client.DeleteScanRowResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

public class DeleteScanRowService {
  private final ScanReadRepository scanReadRepository;
  private final ScanMutationRepository scanMutationRepository;
  private final S3Client s3Client;
  private final String bucket;

  public DeleteScanRowService(
      ScanReadRepository scanReadRepository,
      ScanMutationRepository scanMutationRepository,
      S3Client s3Client,
      String bucket) {
    this.scanReadRepository = scanReadRepository;
    this.scanMutationRepository = scanMutationRepository;
    this.s3Client = s3Client;
    this.bucket = bucket;
  }

  public DeleteScanRowResponse deleteScanRow(RequestContext ctx, DeleteScanRowRequest request)
      throws ServiceException {
    if (request.scanId() == null || request.scanId().isBlank() || request.scanPosition() <= 0) {
      throw new BadRequestException("invalid scan row path parameters");
    }
    var user = ctx.user();
    var scan = scanReadRepository.getScan(user, request.scanId());
    if (scan == null) {
      throw new NotFoundException("Not Found");
    }
    if (!"reviewing".equals(scan.getStatus())) {
      throw new ConflictException("scan is not in a deletable status");
    }
    var row = scanReadRepository.getScanRow(user, request.scanId(), request.scanPosition());
    if (row == null) {
      throw new NotFoundException("Not Found");
    }
    scanMutationRepository.deleteScanRow(user, request.scanId(), request.scanPosition());
    s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(row.getS3Key()).build());
    return new DeleteScanRowResponse();
  }
}
