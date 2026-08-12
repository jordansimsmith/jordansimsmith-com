package com.jordansimsmith.tcginventory;

import com.jordansimsmith.dynamodb.DynamoDbTestModule;
import com.jordansimsmith.http.RequestContextModule;
import com.jordansimsmith.json.ObjectMapperModule;
import com.jordansimsmith.time.ClockTestModule;
import com.jordansimsmith.time.FakeClock;
import dagger.BindsInstance;
import dagger.Component;
import java.net.URI;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Singleton
@Component(
    modules = {
      ObjectMapperModule.class,
      ClockTestModule.class,
      DynamoDbTestModule.class,
      RequestContextModule.class,
      TcgInventoryTestModule.class
    })
public interface TcgInventoryTestFactory extends TcgInventoryFactory {
  FakeClock fakeClock();

  DynamoDbClient dynamoDbClient();

  @Component.Factory
  interface Factory {
    TcgInventoryTestFactory create(@BindsInstance @Named("dynamoDbEndpoint") URI dynamoDbEndpoint);
  }

  static TcgInventoryTestFactory create(URI dynamoDbEndpoint) {
    return DaggerTcgInventoryTestFactory.factory().create(dynamoDbEndpoint);
  }
}
