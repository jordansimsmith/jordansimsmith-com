package com.jordansimsmith.tcginventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.http.HttpResponseFactory;
import com.jordansimsmith.http.RequestContextFactory;
import com.jordansimsmith.queue.QueueClient;
import com.jordansimsmith.secrets.Secrets;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgClient;
import com.jordansimsmith.tcginventory.fetchtcg.FetchTcgTokenMinter;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.ulid.UlidGenerator;
import dagger.Component;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sqs.SqsClient;

@Singleton
@Component(modules = TcgInventoryModule.class)
public interface TcgInventoryFactory {
  ObjectMapper objectMapper();

  Clock clock();

  RequestContextFactory requestContextFactory();

  HttpResponseFactory httpResponseFactory();

  Secrets secrets();

  DynamoDbEnhancedClient dynamoDbEnhancedClient();

  DynamoDbTable<JobItem> jobTable();

  DynamoDbTable<AuditItem> auditTable();

  DynamoDbClient dynamoDbClient();

  SqsClient sqsClient();

  QueueClient<JobMessage> jobsQueue();

  UlidGenerator ulidGenerator();

  FetchTcgClient fetchTcgClient();

  FetchTcgTokenMinter fetchTcgTokenMinter();

  S3Client s3Client();

  S3Presigner s3Presigner();

  static TcgInventoryFactory create() {
    return DaggerTcgInventoryFactory.create();
  }
}
