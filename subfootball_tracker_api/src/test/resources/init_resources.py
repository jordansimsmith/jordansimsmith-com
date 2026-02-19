import boto3
import json
import os

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

topic_name = "subfootball_tracker_api_page_content_updates"
topic_arn = sns_client.create_topic(Name=topic_name)["TopicArn"]

queue_name = "subfootball-tracker-test-queue"
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

table_name = "subfootball_tracker"
dynamodb_client.create_table(
    TableName=table_name,
    AttributeDefinitions=[
        {"AttributeName": "pk", "AttributeType": "S"},
        {"AttributeName": "sk", "AttributeType": "S"},
    ],
    KeySchema=[
        {"AttributeName": "pk", "KeyType": "HASH"},
        {"AttributeName": "sk", "KeyType": "RANGE"},
    ],
    BillingMode="PAY_PER_REQUEST",
)
dynamodb_client.get_waiter("table_exists").wait(TableName=table_name)

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

lambda_env = {
    "SUBFOOTBALL_TRACKER_SUBFOOTBALL_BASE_URL": os.getenv(
        "SUBFOOTBALL_TRACKER_SUBFOOTBALL_BASE_URL", ""
    )
}

with open("/opt/code/localstack/update-page-content-handler_deploy.jar", "rb") as f:
    zip_file_bytes = f.read()

lambda_client.create_function(
    FunctionName="update_page_content_handler",
    Runtime="java21",
    Role=role_arn,
    Handler="com.jordansimsmith.subfootballtracker.UpdatePageContentHandler",
    Code={"ZipFile": zip_file_bytes},
    Timeout=120,
    MemorySize=1024,
    Environment={"Variables": lambda_env},
)

lambda_client.get_waiter("function_active_v2").wait(
    FunctionName="update_page_content_handler"
)
