package com.jordansimsmith.s3;

import dagger.Module;
import dagger.Provides;
import java.net.URI;
import javax.inject.Named;
import javax.inject.Singleton;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Module
public class S3TestModule {
  private static final StaticCredentialsProvider CREDENTIALS =
      StaticCredentialsProvider.create(
          AwsBasicCredentials.create(S3Container.ROOT_USER, S3Container.ROOT_PASSWORD));

  @Provides
  @Singleton
  public S3Client s3Client(@Named("s3Endpoint") URI s3Endpoint) {
    return S3Client.builder()
        .endpointOverride(s3Endpoint)
        .forcePathStyle(true)
        .credentialsProvider(CREDENTIALS)
        .build();
  }

  @Provides
  @Singleton
  public S3Presigner s3Presigner(@Named("s3Endpoint") URI s3Endpoint) {
    return S3Presigner.builder()
        .endpointOverride(s3Endpoint)
        .credentialsProvider(CREDENTIALS)
        .build();
  }
}
