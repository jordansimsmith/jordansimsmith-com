package com.jordansimsmith.tcginventory.scans.server;

import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.scans.client.GetScanRequest;
import com.jordansimsmith.tcginventory.scans.client.GetScanResponse;
import com.jordansimsmith.tcginventory.scans.client.ScanRow;
import com.jordansimsmith.tcginventory.scans.client.ScanRowStatus;
import com.jordansimsmith.tcginventory.scans.client.ScanSuggestion;
import java.util.List;
import javax.annotation.Nullable;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

public class GetScanService {
  private final ScanReadRepository scanRepository;
  private final S3Client s3Client;
  private final S3Presigner s3Presigner;
  private final String bucket;

  public GetScanService(
      ScanReadRepository scanRepository,
      S3Client s3Client,
      S3Presigner s3Presigner,
      String bucket) {
    this.scanRepository = scanRepository;
    this.s3Client = s3Client;
    this.s3Presigner = s3Presigner;
    this.bucket = bucket;
  }

  public GetScanResponse getScan(RequestContext ctx, GetScanRequest request)
      throws ServiceException {
    if (request.scanId() == null || request.scanId().isBlank()) {
      throw new BadRequestException("scan_id is required");
    }
    var scan = scanRepository.getScan(ctx.user(), request.scanId());
    if (scan == null) {
      throw new NotFoundException("Not Found");
    }
    var rows =
        scanRepository.findScanRows(ctx.user(), request.scanId()).stream()
            .map(row -> toRow(scan, row))
            .toList();
    var summary = FindScansService.toSummary(scan);
    return new GetScanResponse(
        summary.scanId(),
        summary.game(),
        summary.status(),
        summary.condition(),
        summary.finish(),
        summary.rowCount(),
        summary.importId(),
        summary.createdAt(),
        rows);
  }

  private ScanRow toRow(ScanItem scan, ScanRowItem row) {
    var sizeBytes = row.getSizeBytes() == null ? 0 : row.getSizeBytes();
    var uploaded = true;
    if ("uploading".equals(scan.getStatus())) {
      try {
        var head =
            s3Client.headObject(
                HeadObjectRequest.builder().bucket(bucket).key(row.getS3Key()).build());
        uploaded =
            head.contentLength() == sizeBytes && ScanImages.CONTENT_TYPE.equals(head.contentType());
      } catch (S3Exception e) {
        if (e.statusCode() == 404) {
          uploaded = false;
        } else {
          throw e;
        }
      }
    }
    var sourceUrl = uploaded ? sourceUrl(row.getS3Key()) : null;
    var status = publicStatus(row.getStatus());
    var suggestions =
        row.getSuggestions() == null
            ? List.<ScanSuggestion>of()
            : row.getSuggestions().stream()
                .map(
                    suggestion ->
                        new ScanSuggestion(
                            suggestion.getExternalId(),
                            suggestion.getName(),
                            suggestion.getScore()))
                .toList();
    return new ScanRow(
        row.getScanPosition() == null ? 0 : row.getScanPosition(),
        row.getFilename(),
        sizeBytes,
        uploaded,
        status,
        Boolean.TRUE.equals(row.getNeedsReview()) || status == ScanRowStatus.NEEDS_REVIEW,
        suggestions,
        sourceUrl);
  }

  private String sourceUrl(String key) {
    return s3Presigner
        .presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(ScanImages.PRESIGN_TTL)
                .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                .build())
        .url()
        .toString();
  }

  @Nullable
  private static ScanRowStatus publicStatus(@Nullable String status) {
    if ("suggested".equals(status)) {
      return ScanRowStatus.SUGGESTED;
    }
    if ("needs_review".equals(status)) {
      return ScanRowStatus.NEEDS_REVIEW;
    }
    return null;
  }
}
