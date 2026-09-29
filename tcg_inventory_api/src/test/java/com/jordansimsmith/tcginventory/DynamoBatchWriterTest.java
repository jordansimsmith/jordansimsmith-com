package com.jordansimsmith.tcginventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

public class DynamoBatchWriterTest {
  private static final String TABLE_NAME = "inventory";

  @Test
  void writeShouldRetryUnprocessedWritesAndSplitAtDynamoDbLimit() {
    // arrange
    var client = mock(DynamoDbClient.class);
    var writes =
        IntStream.range(0, 26)
            .mapToObj(
                index ->
                    WriteRequest.builder()
                        .putRequest(
                            PutRequest.builder()
                                .item(
                                    Map.of(
                                        "pk", AttributeValue.builder().s("item-" + index).build()))
                                .build())
                        .build())
            .toList();
    when(client.batchWriteItem(any(BatchWriteItemRequest.class)))
        .thenReturn(
            BatchWriteItemResponse.builder()
                .unprocessedItems(Map.of(TABLE_NAME, List.of(writes.get(4))))
                .build(),
            BatchWriteItemResponse.builder().build(),
            BatchWriteItemResponse.builder().build());

    // act
    DynamoBatchWriter.write(client, TABLE_NAME, writes);

    // assert
    var requestCaptor = ArgumentCaptor.forClass(BatchWriteItemRequest.class);
    verify(client, times(3)).batchWriteItem(requestCaptor.capture());
    var batches =
        requestCaptor.getAllValues().stream()
            .map(request -> request.requestItems().get(TABLE_NAME))
            .toList();
    assertThat(batches).extracting(List::size).containsExactly(25, 1, 1);
    assertThat(batches.get(1)).containsExactly(writes.get(4));
    assertThat(batches.get(2)).containsExactly(writes.get(25));
  }
}
