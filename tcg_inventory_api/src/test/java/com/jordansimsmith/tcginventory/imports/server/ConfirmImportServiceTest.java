package com.jordansimsmith.tcginventory.imports.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.ConflictException;
import com.jordansimsmith.service.NotFoundException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.imports.client.ConfirmImportRequest;
import com.jordansimsmith.tcginventory.imports.client.ImportDecision;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class ConfirmImportServiceTest {
  private static final String USER = "ctx-user";
  private static final String IMPORT_ID = "opaque/id";
  private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000);

  @Test
  void newConfirmationShouldStartAndSendExactMessage() throws Exception {
    var reads = mock(ImportReadRepository.class);
    var writes = mock(ImportMutationRepository.class);
    var queue = new FakeQueueClient<ImportMessage>();
    var parent = parent("review");
    when(reads.getImport(USER, IMPORT_ID)).thenReturn(parent);
    when(reads.findRows(USER, IMPORT_ID)).thenReturn(List.of(row(1, ImportDecision.KEEP, null, "19.99")));
    var service = new ConfirmImportService(reads, writes, queue, () -> NOW);

    service.confirmImport(new RequestContext(USER), new ConfirmImportRequest(IMPORT_ID));

    verify(writes).startConfirmation(USER, IMPORT_ID, NOW);
    assertThat(queue.getSends())
        .containsExactly(
            new FakeQueueClient.SentMessage<>(
                new ImportMessage(USER, IMPORT_ID, ImportMessageType.CONFIRM),
                IMPORT_ID,
                IMPORT_ID + "#confirm"));
  }

  @Test
  void confirmingAndConfirmedImportsShouldNotReadRowsOrResend() throws Exception {
    var reads = mock(ImportReadRepository.class);
    var writes = mock(ImportMutationRepository.class);
    var queue = mock(QueueClient.class);
    when(reads.getImport(USER, IMPORT_ID)).thenReturn(parent("confirming"), parent("confirmed"));
    var service = new ConfirmImportService(reads, writes, queue, () -> NOW);

    service.confirmImport(new RequestContext(USER), new ConfirmImportRequest(IMPORT_ID));
    service.confirmImport(new RequestContext(USER), new ConfirmImportRequest(IMPORT_ID));

    verify(reads, never()).findRows(USER, IMPORT_ID);
    verify(writes, never()).startConfirmation(USER, IMPORT_ID, NOW);
    verify(queue, never()).send(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
  }

  @Test
  void confirmationShouldRejectMissingInvalidAndNonReviewImports() throws Exception {
    var reads = mock(ImportReadRepository.class);
    var writes = mock(ImportMutationRepository.class);
    var queue = mock(QueueClient.class);
    when(reads.getImport(USER, IMPORT_ID)).thenReturn(null, parent("appraising"));
    var service = new ConfirmImportService(reads, writes, queue, () -> NOW);
    var ctx = new RequestContext(USER);

    assertThatThrownBy(() -> service.confirmImport(ctx, new ConfirmImportRequest(" ")))
        .isInstanceOf(BadRequestException.class);
    assertThatThrownBy(() -> service.confirmImport(ctx, new ConfirmImportRequest(IMPORT_ID)))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> service.confirmImport(ctx, new ConfirmImportRequest(IMPORT_ID)))
        .isInstanceOf(ConflictException.class)
        .hasMessage("import is not in review status");
    verify(reads, times(2)).getImport(USER, IMPORT_ID);
    verify(writes, never()).startConfirmation(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
  }

  @Test
  void onlyPhotoGatedKeepRowsShouldBlockConfirmation() throws Exception {
    var reads = mock(ImportReadRepository.class);
    var writes = mock(ImportMutationRepository.class);
    var queue = mock(QueueClient.class);
    when(reads.getImport(USER, IMPORT_ID)).thenReturn(parent("review"));
    when(reads.findRows(USER, IMPORT_ID))
        .thenReturn(
            List.of(
                row(1, ImportDecision.KEEP, null, "20.00"),
                row(5, ImportDecision.KEEP, List.of(), "20.01"),
                row(2, ImportDecision.KEEP, List.of(ImportRowItem.Photo.create("photo")), "21.00"),
                row(3, ImportDecision.DISCARD, null, "99.00"),
                row(4, ImportDecision.REVIEW, null, "30.00"),
                row(6, ImportDecision.KEEP, null, "1.00")));
    var service = new ConfirmImportService(reads, writes, queue, () -> NOW);

    assertThatThrownBy(
            () -> service.confirmImport(new RequestContext(USER), new ConfirmImportRequest(IMPORT_ID)))
        .isInstanceOf(ConflictException.class)
        .hasMessage("2 rows need photos before confirm");
    verify(writes, never()).startConfirmation(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    verify(queue, never()).send(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
  }

  @Test
  void emptyRowsShouldBeAdmitted() throws Exception {
    var reads = mock(ImportReadRepository.class);
    var writes = mock(ImportMutationRepository.class);
    when(reads.getImport(USER, IMPORT_ID)).thenReturn(parent("review"));
    when(reads.findRows(USER, IMPORT_ID)).thenReturn(List.of());
    var queue = new FakeQueueClient<ImportMessage>();

    new ConfirmImportService(reads, writes, queue, () -> NOW)
        .confirmImport(new RequestContext(USER), new ConfirmImportRequest(IMPORT_ID));

    assertThat(queue.getMessages())
        .containsExactly(new ImportMessage(USER, IMPORT_ID, ImportMessageType.CONFIRM));
  }

  @Test
  void rowsWithoutKeepersShouldBeAdmitted() throws Exception {
    var reads = mock(ImportReadRepository.class);
    var writes = mock(ImportMutationRepository.class);
    when(reads.getImport(USER, IMPORT_ID)).thenReturn(parent("review"));
    when(reads.findRows(USER, IMPORT_ID))
        .thenReturn(
            List.of(
                row(1, ImportDecision.DISCARD, null, "99.00"),
                row(3, ImportDecision.REVIEW, null, "50.00")));
    var queue = new FakeQueueClient<ImportMessage>();

    new ConfirmImportService(reads, writes, queue, () -> NOW)
        .confirmImport(new RequestContext(USER), new ConfirmImportRequest(IMPORT_ID));

    assertThat(queue.getMessages())
        .containsExactly(new ImportMessage(USER, IMPORT_ID, ImportMessageType.CONFIRM));
  }

  @Test
  void queueFailureShouldOccurAfterAdmission() throws Exception {
    var reads = mock(ImportReadRepository.class);
    var writes = mock(ImportMutationRepository.class);
    @SuppressWarnings("unchecked")
    var queue = (QueueClient<ImportMessage>) mock(QueueClient.class);
    when(reads.getImport(USER, IMPORT_ID)).thenReturn(parent("review"));
    when(reads.findRows(USER, IMPORT_ID)).thenReturn(List.of());
    var failure = new IllegalStateException("queue unavailable");
    Mockito.doThrow(failure)
        .when(queue)
        .send(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    var service = new ConfirmImportService(reads, writes, queue, () -> NOW);

    assertThatThrownBy(
            () -> service.confirmImport(new RequestContext(USER), new ConfirmImportRequest(IMPORT_ID)))
        .isSameAs(failure);
    var order = inOrder(writes, queue);
    order.verify(writes).startConfirmation(USER, IMPORT_ID, NOW);
    order.verify(queue).send(ArgumentMatchers.any(), ArgumentMatchers.eq(IMPORT_ID), ArgumentMatchers.eq(IMPORT_ID + "#confirm"));
  }

  private static ImportItem parent(String status) {
    var parent = new ImportItem();
    parent.setStatus(status);
    return parent;
  }

  private static ImportRowItem row(
      int position,
      ImportDecision decision,
      List<ImportRowItem.Photo> photos,
      String suggestedPrice) {
    var row = new ImportRowItem();
    row.setPosition(position);
    row.setDecision(decision.name().toLowerCase());
    row.setPhotos(photos);
    row.setSuggestedPrice(suggestedPrice);
    return row;
  }
}
