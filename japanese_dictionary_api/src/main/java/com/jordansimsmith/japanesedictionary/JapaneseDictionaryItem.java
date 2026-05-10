package com.jordansimsmith.japanesedictionary;

import java.util.Objects;
import javax.annotation.Nullable;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class JapaneseDictionaryItem {
  public static final String DELIMITER = "#";
  public static final String TERM_PREFIX = "TERM" + DELIMITER;

  public static final String EXPRESSION_PARTITION = "EXPRESSION";
  public static final String READING_PARTITION = "READING";
  public static final String ROMAJI_PARTITION = "ROMAJI";

  public static final String TABLE_NAME = "japanese_dictionary";
  public static final String GSI1_NAME = "gsi1";
  public static final String GSI2_NAME = "gsi2";
  public static final String GSI3_NAME = "gsi3";

  public static final String PK = "pk";
  public static final String SK = "sk";
  public static final String GSI1PK = "gsi1pk";
  public static final String GSI1SK = "gsi1sk";
  public static final String GSI2PK = "gsi2pk";
  public static final String GSI2SK = "gsi2sk";
  public static final String GSI3PK = "gsi3pk";
  public static final String GSI3SK = "gsi3sk";
  public static final String SEQUENCE = "sequence";
  public static final String EXPRESSION = "expression";
  public static final String READING = "reading";
  public static final String READING_ROMAJI = "reading_romaji";
  public static final String FREQUENCY_RANK = "frequency_rank";
  public static final String PITCH = "pitch";
  public static final String GLOSSARY_RAW = "glossary_raw";

  private String pk;
  private String sk;
  private String gsi1pk;
  private String gsi1sk;
  private String gsi2pk;
  private String gsi2sk;
  private String gsi3pk;
  private String gsi3sk;
  private Long sequence;
  private String expression;
  private String reading;
  private String readingRomaji;
  @Nullable private Integer frequencyRank;
  @Nullable private Integer pitch;
  private String glossaryRaw;

  @DynamoDbPartitionKey
  @DynamoDbAttribute(PK)
  public String getPk() {
    return pk;
  }

  public void setPk(String pk) {
    this.pk = pk;
  }

  @DynamoDbSortKey
  @DynamoDbAttribute(SK)
  public String getSk() {
    return sk;
  }

  public void setSk(String sk) {
    this.sk = sk;
  }

  @DynamoDbSecondaryPartitionKey(indexNames = GSI1_NAME)
  @DynamoDbAttribute(GSI1PK)
  public String getGsi1pk() {
    return gsi1pk;
  }

  public void setGsi1pk(String gsi1pk) {
    this.gsi1pk = gsi1pk;
  }

  @DynamoDbSecondarySortKey(indexNames = GSI1_NAME)
  @DynamoDbAttribute(GSI1SK)
  public String getGsi1sk() {
    return gsi1sk;
  }

  public void setGsi1sk(String gsi1sk) {
    this.gsi1sk = gsi1sk;
  }

  @DynamoDbSecondaryPartitionKey(indexNames = GSI2_NAME)
  @DynamoDbAttribute(GSI2PK)
  public String getGsi2pk() {
    return gsi2pk;
  }

  public void setGsi2pk(String gsi2pk) {
    this.gsi2pk = gsi2pk;
  }

  @DynamoDbSecondarySortKey(indexNames = GSI2_NAME)
  @DynamoDbAttribute(GSI2SK)
  public String getGsi2sk() {
    return gsi2sk;
  }

  public void setGsi2sk(String gsi2sk) {
    this.gsi2sk = gsi2sk;
  }

  @DynamoDbSecondaryPartitionKey(indexNames = GSI3_NAME)
  @DynamoDbAttribute(GSI3PK)
  public String getGsi3pk() {
    return gsi3pk;
  }

  public void setGsi3pk(String gsi3pk) {
    this.gsi3pk = gsi3pk;
  }

  @DynamoDbSecondarySortKey(indexNames = GSI3_NAME)
  @DynamoDbAttribute(GSI3SK)
  public String getGsi3sk() {
    return gsi3sk;
  }

  public void setGsi3sk(String gsi3sk) {
    this.gsi3sk = gsi3sk;
  }

  @DynamoDbAttribute(SEQUENCE)
  public Long getSequence() {
    return sequence;
  }

  public void setSequence(Long sequence) {
    this.sequence = sequence;
  }

  @DynamoDbAttribute(EXPRESSION)
  public String getExpression() {
    return expression;
  }

  public void setExpression(String expression) {
    this.expression = expression;
  }

  @DynamoDbAttribute(READING)
  public String getReading() {
    return reading;
  }

  public void setReading(String reading) {
    this.reading = reading;
  }

  @DynamoDbAttribute(READING_ROMAJI)
  public String getReadingRomaji() {
    return readingRomaji;
  }

  public void setReadingRomaji(String readingRomaji) {
    this.readingRomaji = readingRomaji;
  }

  @Nullable
  @DynamoDbAttribute(FREQUENCY_RANK)
  public Integer getFrequencyRank() {
    return frequencyRank;
  }

  public void setFrequencyRank(@Nullable Integer frequencyRank) {
    this.frequencyRank = frequencyRank;
  }

  @Nullable
  @DynamoDbAttribute(PITCH)
  public Integer getPitch() {
    return pitch;
  }

  public void setPitch(@Nullable Integer pitch) {
    this.pitch = pitch;
  }

  @DynamoDbAttribute(GLOSSARY_RAW)
  public String getGlossaryRaw() {
    return glossaryRaw;
  }

  public void setGlossaryRaw(String glossaryRaw) {
    this.glossaryRaw = glossaryRaw;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    JapaneseDictionaryItem that = (JapaneseDictionaryItem) o;
    return Objects.equals(pk, that.pk)
        && Objects.equals(sk, that.sk)
        && Objects.equals(gsi1pk, that.gsi1pk)
        && Objects.equals(gsi1sk, that.gsi1sk)
        && Objects.equals(gsi2pk, that.gsi2pk)
        && Objects.equals(gsi2sk, that.gsi2sk)
        && Objects.equals(gsi3pk, that.gsi3pk)
        && Objects.equals(gsi3sk, that.gsi3sk)
        && Objects.equals(sequence, that.sequence)
        && Objects.equals(expression, that.expression)
        && Objects.equals(reading, that.reading)
        && Objects.equals(readingRomaji, that.readingRomaji)
        && Objects.equals(frequencyRank, that.frequencyRank)
        && Objects.equals(pitch, that.pitch)
        && Objects.equals(glossaryRaw, that.glossaryRaw);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        pk,
        sk,
        gsi1pk,
        gsi1sk,
        gsi2pk,
        gsi2sk,
        gsi3pk,
        gsi3sk,
        sequence,
        expression,
        reading,
        readingRomaji,
        frequencyRank,
        pitch,
        glossaryRaw);
  }

  @Override
  public String toString() {
    return "JapaneseDictionaryItem{"
        + "pk='"
        + pk
        + '\''
        + ", sk='"
        + sk
        + '\''
        + ", sequence="
        + sequence
        + ", expression='"
        + expression
        + '\''
        + ", reading='"
        + reading
        + '\''
        + ", readingRomaji='"
        + readingRomaji
        + '\''
        + ", frequencyRank="
        + frequencyRank
        + ", pitch="
        + pitch
        + '}';
  }

  public static String formatPk(long sequence) {
    return TERM_PREFIX + sequence;
  }

  public static String formatSk(long sequence) {
    return TERM_PREFIX + sequence;
  }

  public static String formatGsi1pk() {
    return EXPRESSION_PARTITION;
  }

  public static String formatGsi1sk(String expression) {
    return expression;
  }

  public static String formatGsi2pk() {
    return READING_PARTITION;
  }

  public static String formatGsi2sk(String reading) {
    return reading;
  }

  public static String formatGsi3pk() {
    return ROMAJI_PARTITION;
  }

  public static String formatGsi3sk(String readingRomaji) {
    return readingRomaji;
  }

  public static JapaneseDictionaryItem create(
      long sequence,
      String expression,
      String reading,
      String readingRomaji,
      @Nullable Integer frequencyRank,
      @Nullable Integer pitch,
      String glossaryRaw) {
    var item = new JapaneseDictionaryItem();
    item.setPk(formatPk(sequence));
    item.setSk(formatSk(sequence));
    item.setGsi1pk(formatGsi1pk());
    item.setGsi1sk(formatGsi1sk(expression));
    item.setGsi2pk(formatGsi2pk());
    item.setGsi2sk(formatGsi2sk(reading));
    item.setGsi3pk(formatGsi3pk());
    item.setGsi3sk(formatGsi3sk(readingRomaji));
    item.setSequence(sequence);
    item.setExpression(expression);
    item.setReading(reading);
    item.setReadingRomaji(readingRomaji);
    item.setFrequencyRank(frequencyRank);
    item.setPitch(pitch);
    item.setGlossaryRaw(glossaryRaw);
    return item;
  }
}
