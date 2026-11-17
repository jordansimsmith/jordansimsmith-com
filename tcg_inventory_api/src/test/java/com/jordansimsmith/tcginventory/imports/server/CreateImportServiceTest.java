package com.jordansimsmith.tcginventory.imports.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.jordansimsmith.queue.FakeQueueClient;
import com.jordansimsmith.service.BadRequestException;
import com.jordansimsmith.service.RequestContext;
import com.jordansimsmith.tcginventory.games.client.FakeGamesService;
import com.jordansimsmith.tcginventory.imports.client.CreateImportRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CreateImportServiceTest {
  @Test
  void createImportShouldRejectJapanesePokemonCsv() {
    // arrange
    var reads = mock(ImportReadRepository.class);
    var writes = mock(ImportMutationRepository.class);
    var games = new FakeGamesService();
    var queue = new FakeQueueClient<ImportMessage>();
    var service =
        new CreateImportService(
            reads,
            writes,
            games,
            new ImportCsvParserFactory(new ManaBoxCsvParser()),
            queue,
            () -> Instant.EPOCH,
            () -> "unused");
    var context = new RequestContext("alice");
    var request = new CreateImportRequest("japanese-csv", "pokemon_jp", "csv", null, null);

    // act
    var failure = catchThrowable(() -> service.createImport(context, request));

    // assert
    assertThat(failure)
        .isInstanceOf(BadRequestException.class)
        .hasMessage("CSV import is unavailable for game: pokemon_jp");
    verify(reads).getCreateImportOperation("alice", "japanese-csv");
    verifyNoMoreInteractions(reads);
    verifyNoInteractions(writes);
    assertThat(queue.getSends()).isEmpty();
  }
}
