package com.jordansimsmith.tcginventory.scans.server;

import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.scans.client.IdentifyScanRequest;
import com.jordansimsmith.tcginventory.scans.client.IdentifyScanResponse;

public class IdentifyScanService {
  private final ScanReadRepository scanReadRepository;
  private final ScanMutationRepository scanMutationRepository;
  private final ScanUploadVerifier scanUploadVerifier;
  private final QueueClient<ScanMessage> scanQueue;

  public IdentifyScanService(
      ScanReadRepository scanReadRepository,
      ScanMutationRepository scanMutationRepository,
      ScanUploadVerifier scanUploadVerifier,
      QueueClient<ScanMessage> scanQueue) {
    this.scanReadRepository = scanReadRepository;
    this.scanMutationRepository = scanMutationRepository;
    this.scanUploadVerifier = scanUploadVerifier;
    this.scanQueue = scanQueue;
  }

  public IdentifyScanResponse identifyScan(RequestContext ctx, IdentifyScanRequest request)
      throws ServiceException {
    if (request.scanId() == null || request.scanId().isBlank()) {
      throw new BadRequestException("scan_id is required");
    }
    var scan = scanReadRepository.getScan(ctx.user(), request.scanId());
    if (scan == null) {
      throw new NotFoundException("Not Found");
    }
    if (!"uploading".equals(scan.getStatus())) {
      return new IdentifyScanResponse();
    }
    var rows = scanReadRepository.findScanRows(ctx.user(), request.scanId());
    if (!scanUploadVerifier.areUploaded(rows)) {
      throw new ConflictException("all scan files must be uploaded before identification");
    }
    scanMutationRepository.startScanIdentification(ctx.user(), request.scanId());
    var message = new ScanMessage(ctx.user(), request.scanId());
    var deduplicationId = request.scanId() + "#0";
    scanQueue.send(message, request.scanId(), deduplicationId);
    return new IdentifyScanResponse();
  }
}
