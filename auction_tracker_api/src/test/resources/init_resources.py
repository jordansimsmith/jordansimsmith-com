import boto3
import json
import os
import time

region_name = "ap-southeast-2"
endpoint_url = "http://localhost:4566"

iam_client = boto3.client("iam", endpoint_url=endpoint_url, region_name=region_name)
lambda_client = boto3.client(
    "lambda", endpoint_url=endpoint_url, region_name=region_name
)
dynamodb_client = boto3.client(
    "dynamodb", endpoint_url=endpoint_url, region_name=region_name
)
sqs_client = boto3.client("sqs", endpoint_url=endpoint_url, region_name=region_name)
sns_client = boto3.client("sns", endpoint_url=endpoint_url, region_name=region_name)

topic_name = "auction_tracker_api_digest"
topic_response = sns_client.create_topic(Name=topic_name)
topic_arn = topic_response["TopicArn"]

queue_name = "auction-tracker-test-queue"
queue_url = sqs_client.create_queue(QueueName=queue_name)["QueueUrl"]
queue_attributes = sqs_client.get_queue_attributes(
    QueueUrl=queue_url, AttributeNames=["QueueArn"]
)
queue_arn = queue_attributes["Attributes"]["QueueArn"]
queue_policy = {
    "Version": "2012-10-17",
    "Id": "AllowAll",
    "Statement": [
        {
            "Sid": "AllowAllActions",
            "Effect": "Allow",
            "Principal": "*",
            "Action": "*",
            "Resource": "*",
        }
    ],
}
sqs_client.set_queue_attributes(
    QueueUrl=queue_url, Attributes={"Policy": json.dumps(queue_policy)}
)

sns_client.subscribe(TopicArn=topic_arn, Protocol="sqs", Endpoint=queue_arn)

table_name = "auction_tracker"
dynamodb_client.create_table(
    TableName=table_name,
    AttributeDefinitions=[
        {"AttributeName": "pk", "AttributeType": "S"},
        {"AttributeName": "sk", "AttributeType": "S"},
        {"AttributeName": "gsi1pk", "AttributeType": "S"},
        {"AttributeName": "gsi1sk", "AttributeType": "S"},
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
        }
    ],
    BillingMode="PAY_PER_REQUEST",
)
dynamodb_client.get_waiter("table_exists").wait(TableName=table_name)

while True:
    table_desc = dynamodb_client.describe_table(TableName=table_name)
    gsi_status = table_desc["Table"]["GlobalSecondaryIndexes"][0]["IndexStatus"]
    if gsi_status == "ACTIVE":
        break
    time.sleep(1)

role_name = "lambda-execution-role"
policy_name = "lambda-execution-policy"
role_arn = f"arn:aws:iam::000000000000:role/{role_name}"
assume_role_policy_document = {
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Principal": {"Service": "lambda.amazonaws.com"},
            "Action": "sts:AssumeRole",
        }
    ],
}
iam_client.create_role(
    RoleName=role_name, AssumeRolePolicyDocument=json.dumps(assume_role_policy_document)
)
policy_document = {
    "Version": "2012-10-17",
    "Statement": [{"Effect": "Allow", "Action": "*", "Resource": "*"}],
}
iam_client.put_role_policy(
    RoleName=role_name,
    PolicyName=policy_name,
    PolicyDocument=json.dumps(policy_document),
)

configs = [
    {
        "function_name": "update_items_handler",
        "handler_name": "com.jordansimsmith.auctiontracker.UpdateItemsHandler",
        "zip_file": "update-items-handler_deploy.jar",
    },
    {
        "function_name": "send_digest_handler",
        "handler_name": "com.jordansimsmith.auctiontracker.SendDigestHandler",
        "zip_file": "send-digest-handler_deploy.jar",
    },
]

lambda_env = {
    "AUCTION_TRACKER_TRADEME_BASE_URL": os.getenv(
        "AUCTION_TRACKER_TRADEME_BASE_URL", ""
    )
}

# create all lambda functions
for config in configs:
    with open(f"/opt/code/localstack/{config['zip_file']}", "rb") as f:
        zip_file_bytes = f.read()

    lambda_client.create_function(
        FunctionName=config["function_name"],
        Runtime="java21",
        Role=role_arn,
        Handler=config["handler_name"],
        Code={"ZipFile": zip_file_bytes},
        Timeout=30,
        MemorySize=1024,
        Architectures=["x86_64"],
        Environment={"Variables": lambda_env},
    )

# wait for all lambda functions to be active
for config in configs:
    lambda_client.get_waiter("function_active_v2").wait(
        FunctionName=config["function_name"]
    )
