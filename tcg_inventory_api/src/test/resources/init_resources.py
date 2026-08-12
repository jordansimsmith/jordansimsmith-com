import boto3
import json
import time

region_name = "ap-southeast-2"
endpoint_url = "http://localhost:4566"

dynamodb_client = boto3.client(
    "dynamodb", endpoint_url=endpoint_url, region_name=region_name
)

table_name = "tcg_inventory"
dynamodb_client.create_table(
    TableName=table_name,
    AttributeDefinitions=[
        {"AttributeName": "pk", "AttributeType": "S"},
        {"AttributeName": "sk", "AttributeType": "S"},
        {"AttributeName": "gsi1pk", "AttributeType": "S"},
        {"AttributeName": "gsi1sk", "AttributeType": "S"},
        {"AttributeName": "gsi2pk", "AttributeType": "S"},
        {"AttributeName": "gsi2sk", "AttributeType": "S"},
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
            "Projection": {"ProjectionType": "ALL"},
        },
        {
            "IndexName": "gsi2",
            "KeySchema": [
                {"AttributeName": "gsi2pk", "KeyType": "HASH"},
                {"AttributeName": "gsi2sk", "KeyType": "RANGE"},
            ],
            "Projection": {"ProjectionType": "ALL"},
        },
    ],
    BillingMode="PAY_PER_REQUEST",
)
dynamodb_client.get_waiter("table_exists").wait(TableName=table_name)

while True:
    table_desc = dynamodb_client.describe_table(TableName=table_name)
    gsis = table_desc["Table"]["GlobalSecondaryIndexes"]
    if all(gsi["IndexStatus"] == "ACTIVE" for gsi in gsis):
        break
    time.sleep(1)
