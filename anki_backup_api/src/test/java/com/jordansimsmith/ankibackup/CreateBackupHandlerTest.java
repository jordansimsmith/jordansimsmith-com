package com.jordansimsmith.ankibackup;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;

public class CreateBackupHandlerTest {

  private int calculatePartCount(long sizeBytes) {
    var totalParts = (int) Math.ceil((double) sizeBytes / CreateBackupHandler.PART_SIZE_BYTES);
    if (totalParts == 0) {
      totalParts = 1;
    }
    return totalParts;
  }

  @Test
  void partCountShouldBeOneWhenZeroBytes() {
    assertThat(calculatePartCount(0)).isEqualTo(1);
  }

  @Test
  void partCountShouldBeOneWhenOneByte() {
    assertThat(calculatePartCount(1)).isEqualTo(1);
  }

  @Test
  void partCountShouldBeOneWhenExactlyOnePartSize() {
    assertThat(calculatePartCount(CreateBackupHandler.PART_SIZE_BYTES)).isEqualTo(1);
  }

  @Test
  void partCountShouldBeTwoWhenOneByteOverPartSize() {
    assertThat(calculatePartCount(CreateBackupHandler.PART_SIZE_BYTES + 1)).isEqualTo(2);
  }

  @Test
  void partCountShouldBeTwoWhenExactlyDoublePartSize() {
    assertThat(calculatePartCount(CreateBackupHandler.PART_SIZE_BYTES * 2)).isEqualTo(2);
  }

  @Test
  void partCountShouldBeEightWhenFiveHundredMegabyteArtifact() {
    // 534,773,760 bytes from README example (~510 MB)
    assertThat(calculatePartCount(534_773_760L)).isEqualTo(8);
  }

  @Test
  void partCountShouldBeEightyWhenFiveGigabyteArtifact() {
    assertThat(calculatePartCount(5L * 1024 * 1024 * 1024)).isEqualTo(80);
  }

  @Test
  void partCountShouldBeOneWhenOneByteUnderPartSize() {
    assertThat(calculatePartCount(CreateBackupHandler.PART_SIZE_BYTES - 1)).isEqualTo(1);
  }

  @Test
  void intervalStartShouldBeTwentyFourHoursBeforeNow() {
    // arrange
    var now = Instant.parse("2026-03-01T12:00:00Z");

    // act
    var intervalStart = now.minus(Duration.ofHours(CreateBackupHandler.BACKUP_INTERVAL_HOURS));

    // assert
    assertThat(intervalStart).isEqualTo(Instant.parse("2026-02-28T12:00:00Z"));
  }

  @Test
  void expiresAtShouldBeNinetyDaysFromCreatedAt() {
    // arrange
    var createdAt = Instant.parse("2026-03-01T10:23:01Z");

    // act
    var expiresAt = createdAt.plus(Duration.ofDays(CreateBackupHandler.RETENTION_DAYS));

    // assert
    assertThat(expiresAt).isEqualTo(Instant.parse("2026-05-30T10:23:01Z"));
  }

  @Test
  void ttlShouldBeExpiresAtEpochSeconds() {
    // arrange
    var createdAt = Instant.parse("2026-03-01T10:23:01Z");
    var expiresAt = createdAt.plus(Duration.ofDays(CreateBackupHandler.RETENTION_DAYS));

    // act
    var ttl = expiresAt.getEpochSecond();

    // assert
    assertThat(ttl).isGreaterThan(createdAt.getEpochSecond());
    assertThat(ttl)
        .isEqualTo(
            createdAt.plus(Duration.ofDays(CreateBackupHandler.RETENTION_DAYS)).getEpochSecond());
  }

  @Test
  void s3KeyShouldFollowExpectedFormat() {
    // arrange
    var user = "alice";
    var profileId = "japanese-main";
    var createdAt = Instant.parse("2026-03-01T10:23:01Z");
    var backupId = "550e8400-e29b-41d4-a716-446655440000";
    var datePrefix =
        DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC).format(createdAt);

    // act
    var s3Key =
        "users/%s/profiles/%s/backups/%s/%s.colpkg"
            .formatted(user, profileId, datePrefix, backupId);

    // assert
    assertThat(s3Key)
        .isEqualTo(
            "users/alice/profiles/japanese-main/backups/2026/03/01/550e8400-e29b-41d4-a716-446655440000.colpkg");
  }

  @Test
  void createBackupConstantsShouldMatchReadmeSpecification() {
    assertThat(CreateBackupHandler.BUCKET).isEqualTo("anki-backup.jordansimsmith.com");
    assertThat(CreateBackupHandler.PART_SIZE_BYTES).isEqualTo(67_108_864L);
    assertThat(CreateBackupHandler.BACKUP_INTERVAL_HOURS).isEqualTo(24);
    assertThat(CreateBackupHandler.RETENTION_DAYS).isEqualTo(90);
    assertThat(CreateBackupHandler.UPLOAD_URL_TTL_SECONDS).isEqualTo(3600);
  }

  @Test
  void getBackupConstantsShouldMatchReadmeSpecification() {
    assertThat(GetBackupHandler.BUCKET).isEqualTo("anki-backup.jordansimsmith.com");
    assertThat(GetBackupHandler.DOWNLOAD_URL_TTL_SECONDS).isEqualTo(3600);
  }

  @Test
  void updateBackupBucketConstantShouldMatchReadmeSpecification() {
    assertThat(UpdateBackupHandler.BUCKET).isEqualTo("anki-backup.jordansimsmith.com");
  }
}
