package com.jordansimsmith.tcginventory.scans.server;

import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.service.ServiceException;
import com.jordansimsmith.tcginventory.scans.client.ConfirmScanRequest;
import com.jordansimsmith.tcginventory.scans.client.ConfirmScanResponse;
import com.jordansimsmith.tcginventory.scans.client.ScanSelection;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

public class ConfirmScanService {
  private final ScanReadRepository scanReadRepository;
  private final ScanMutationRepository scanMutationRepository;
  private final QueueClient<ScanConfirmationMessage> queue;

  public ConfirmScanService(
      ScanReadRepository scanReadRepository,
      ScanMutationRepository scanMutationRepository,
      QueueClient<ScanConfirmationMessage> queue) {
    this.scanReadRepository = scanReadRepository;
    this.scanMutationRepository = scanMutationRepository;
    this.queue = queue;
  }

  public ConfirmScanResponse confirmScan(RequestContext ctx, ConfirmScanRequest request)
      throws ServiceException {
    var scanId = request.scanId();
    if (scanId == null || scanId.isBlank()) {
      throw new BadRequestException("scan_id is required");
    }
    var user = ctx.user();
    var scan = scanReadRepository.getScan(user, scanId);
    if (scan == null) {
      throw new NotFoundException("Not Found");
    }
    var status = scan.getStatus();
    if ("confirming".equals(status) || "confirmed".equals(status)) {
      return new ConfirmScanResponse();
    }
    if (!"reviewing".equals(status)) {
      throw new ConflictException("scan is not in reviewing status");
    }

    var version = scan.getVersion() == null ? 0L : scan.getVersion();
    var rows = scanReadRepository.findScanRows(user, scanId);
    validateSelections(request.rows(), rows);

    scanMutationRepository.claimScanConfirmation(user, scanId, version);
    var attemptVersion = version + 1;
    for (var selection : request.rows()) {
      scanMutationRepository.updateScanRowSelection(
          user, scanId, attemptVersion, selection.scanPosition(), selection.externalId());
    }
    scanMutationRepository.startScanConfirmation(user, scanId, attemptVersion);

    queue.send(new ScanConfirmationMessage(user, scanId), scanId, scanId);
    return new ConfirmScanResponse();
  }

  private void validateSelections(List<ScanSelection> selections, List<ScanRowItem> rows)
      throws BadRequestException {
    var message = "every retained scan row must be selected exactly once";
    if (selections == null || selections.isEmpty() || selections.size() != rows.size()) {
      throw new BadRequestException(message);
    }
    var remainingPositions =
        rows.stream()
            .map(ScanRowItem::getScanPosition)
            .collect(Collectors.toCollection(HashSet::new));
    for (var selection : selections) {
      if (selection == null || !remainingPositions.remove(selection.scanPosition())) {
        throw new BadRequestException(message);
      }
      if (selection.externalId() == null || selection.externalId().isBlank()) {
        throw new BadRequestException(
            "scan position %d: external_id is required".formatted(selection.scanPosition()));
      }
    }
  }
}
