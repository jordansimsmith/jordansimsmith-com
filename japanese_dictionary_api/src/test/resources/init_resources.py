import json
import time

import boto3

region_name = "ap-southeast-2"
endpoint_url = "http://localhost:4566"

secretsmanager_client = boto3.client(
    "secretsmanager", endpoint_url=endpoint_url, region_name=region_name
)
dynamodb_client = boto3.client(
    "dynamodb", endpoint_url=endpoint_url, region_name=region_name
)

secretsmanager_client.create_secret(
    Name="japanese_dictionary_api",
    SecretString=json.dumps({"users": [{"user": "testuser", "password": "testpass"}]}),
)

table_name = "japanese_dictionary"
gsi_projection = {
    "ProjectionType": "INCLUDE",
    "NonKeyAttributes": ["sequence", "frequency_rank"],
}
dynamodb_client.create_table(
    TableName=table_name,
    AttributeDefinitions=[
        {"AttributeName": "pk", "AttributeType": "S"},
        {"AttributeName": "sk", "AttributeType": "S"},
        {"AttributeName": "gsi1pk", "AttributeType": "S"},
        {"AttributeName": "gsi1sk", "AttributeType": "S"},
        {"AttributeName": "gsi2pk", "AttributeType": "S"},
        {"AttributeName": "gsi2sk", "AttributeType": "S"},
        {"AttributeName": "gsi3pk", "AttributeType": "S"},
        {"AttributeName": "gsi3sk", "AttributeType": "S"},
    ],
    KeySchema=[
        {"AttributeName": "pk", "KeyType": "HASH"},
        {"AttributeName": "sk", "KeyType": "RANGE"},
    ],
    GlobalSecondaryIndexes=[
        {
            "IndexName": "gsi1",
            "KeySchema": [
                {"AttributeName": "gsi1pk", "KeyType": "HASH"},
                {"AttributeName": "gsi1sk", "KeyType": "RANGE"},
            ],
            "Projection": gsi_projection,
        },
        {
            "IndexName": "gsi2",
            "KeySchema": [
                {"AttributeName": "gsi2pk", "KeyType": "HASH"},
                {"AttributeName": "gsi2sk", "KeyType": "RANGE"},
            ],
            "Projection": gsi_projection,
        },
        {
            "IndexName": "gsi3",
            "KeySchema": [
                {"AttributeName": "gsi3pk", "KeyType": "HASH"},
                {"AttributeName": "gsi3sk", "KeyType": "RANGE"},
            ],
            "Projection": gsi_projection,
        },
    ],
    BillingMode="PAY_PER_REQUEST",
)
dynamodb_client.get_waiter("table_exists").wait(TableName=table_name)

while True:
    table_desc = dynamodb_client.describe_table(TableName=table_name)
    statuses = [
        idx["IndexStatus"] for idx in table_desc["Table"]["GlobalSecondaryIndexes"]
    ]
    if all(status == "ACTIVE" for status in statuses):
        break
    time.sleep(1)


def make_item(
    sequence,
    expression,
    reading,
    reading_romaji,
    frequency_rank,
    pitch,
    glossary_raw,
):
    pk = f"TERM#{sequence}"
    item = {
        "pk": {"S": pk},
        "sk": {"S": pk},
        "gsi1pk": {"S": "EXPRESSION"},
        "gsi1sk": {"S": expression},
        "gsi2pk": {"S": "READING"},
        "gsi2sk": {"S": reading},
        "gsi3pk": {"S": "ROMAJI"},
        "gsi3sk": {"S": reading_romaji},
        "sequence": {"N": str(sequence)},
        "expression": {"S": expression},
        "reading": {"S": reading},
        "reading_romaji": {"S": reading_romaji},
        "glossary_raw": {"S": json.dumps(glossary_raw, ensure_ascii=False)},
    }
    if frequency_rank is not None:
        item["frequency_rank"] = {"N": str(frequency_rank)}
    if pitch is not None:
        item["pitch"] = {"N": str(pitch)}
    return item


fixtures = [
    make_item(1, "新", "しん", "shin", None, 0, {"tag": "div", "content": "new"}),
    make_item(
        2,
        "新橋",
        "しんばし",
        "shinbashi",
        18472,
        0,
        {"tag": "div", "content": "Shinbashi"},
    ),
    make_item(
        3,
        "新しい",
        "あたらしい",
        "atarashii",
        200,
        3,
        {"tag": "div", "content": "new (adj)"},
    ),
    make_item(
        4,
        "しんぱい",
        "しんぱい",
        "shinpai",
        5000,
        0,
        {"tag": "div", "content": "worry"},
    ),
    make_item(
        5,
        "心",
        "しん",
        "shin",
        None,
        1,
        {
            "tag": "img",
            "path": "jitendex/graphics/heart.png",
            "description": "heart",
        },
    ),
]
for fixture in fixtures:
    dynamodb_client.put_item(TableName=table_name, Item=fixture)
