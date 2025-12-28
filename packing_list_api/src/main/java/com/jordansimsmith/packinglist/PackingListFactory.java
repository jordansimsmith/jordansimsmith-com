package com.jordansimsmith.packinglist;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jordansimsmith.dynamodb.DynamoDbModule;
import com.jordansimsmith.json.ObjectMapperModule;
import com.jordansimsmith.secrets.Secrets;
import com.jordansimsmith.secrets.SecretsModule;
import com.jordansimsmith.time.Clock;
import com.jordansimsmith.time.ClockModule;
import dagger.Component;
import javax.inject.Singleton;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;

@Singleton
@Component(
    modules = {
      ObjectMapperModule.class,
      SecretsModule.class,
      ClockModule.class,
      DynamoDbModule.class,
      PackingListModule.class
    })
public interface PackingListFactory {
  ObjectMapper objectMapper();

  Secrets secrets();

  Clock clock();

  TemplatesFactory templatesFactory();

  DynamoDbTable<PackingListItem> packingListTable();

  TripValidator tripValidator();

  static PackingListFactory create() {
    return DaggerPackingListFactory.create();
  }
}
