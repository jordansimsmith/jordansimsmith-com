import boto3
import json
import time

region_name = "ap-southeast-2"
endpoint_url = "http://localhost:4566"

iam_client = boto3.client("iam", endpoint_url=endpoint_url, region_name=region_name)
lambda_client = boto3.client(
    "lambda", endpoint_url=endpoint_url, region_name=region_name
)
apigateway_client = boto3.client(
    "apigateway", endpoint_url=endpoint_url, region_name=region_name
)
dynamodb_client = boto3.client(
    "dynamodb", endpoint_url=endpoint_url, region_name=region_name
)
sqs_client = boto3.client("sqs", endpoint_url=endpoint_url, region_name=region_name)

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

queue_name = "tcg_inventory_jobs"
queue_url = sqs_client.create_queue(QueueName=queue_name)["QueueUrl"]
queue_arn = sqs_client.get_queue_attributes(
    QueueUrl=queue_url, AttributeNames=["QueueArn"]
)["Attributes"]["QueueArn"]

api_id = apigateway_client.create_rest_api(
    name="tcg_inventory", tags={"_custom_id_": "tcg_inventory"}
)["id"]
root_id = apigateway_client.get_resources(restApiId=api_id)["items"][0]["id"]

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
    RoleName=role_name,
    AssumeRolePolicyDocument=json.dumps(assume_role_policy_document),
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

lambdas = {
    "get_settings": {
        "handler": "com.jordansimsmith.tcginventory.GetSettingsHandler",
        "zip_file": "get-settings-handler_deploy.jar",
    },
    "put_settings": {
        "handler": "com.jordansimsmith.tcginventory.PutSettingsHandler",
        "zip_file": "put-settings-handler_deploy.jar",
    },
    "create_import": {
        "handler": "com.jordansimsmith.tcginventory.CreateImportHandler",
        "zip_file": "create-import-handler_deploy.jar",
    },
    "find_imports": {
        "handler": "com.jordansimsmith.tcginventory.FindImportsHandler",
        "zip_file": "find-imports-handler_deploy.jar",
    },
    "get_import": {
        "handler": "com.jordansimsmith.tcginventory.GetImportHandler",
        "zip_file": "get-import-handler_deploy.jar",
    },
    "delete_import": {
        "handler": "com.jordansimsmith.tcginventory.DeleteImportHandler",
        "zip_file": "delete-import-handler_deploy.jar",
    },
    "create_publish": {
        "handler": "com.jordansimsmith.tcginventory.CreatePublishHandler",
        "zip_file": "create-publish-handler_deploy.jar",
    },
    "get_publish": {
        "handler": "com.jordansimsmith.tcginventory.GetPublishHandler",
        "zip_file": "get-publish-handler_deploy.jar",
    },
    "jobs_handler": {
        "handler": "com.jordansimsmith.tcginventory.JobsHandler",
        "zip_file": "jobs-handler_deploy.jar",
        "timeout": 900,
        "environment": {"JOBS_QUEUE_URL": queue_url},
    },
}

root_resources = {
    "settings": {"path": "settings"},
    "imports": {"path": "imports"},
    "publish": {"path": "publish"},
}

child_resources = {
    "import_detail": {"parent": "imports", "path": "{import_id}"},
    "import_confirm": {"parent": "import_detail", "path": "confirm"},
}

endpoints = {
    "get_settings": {"resource": "settings", "method": "GET", "lambda": "get_settings"},
    "put_settings": {"resource": "settings", "method": "PUT", "lambda": "put_settings"},
    "create_import": {
        "resource": "imports",
        "method": "POST",
        "lambda": "create_import",
    },
    "find_imports": {"resource": "imports", "method": "GET", "lambda": "find_imports"},
    "get_import": {
        "resource": "import_detail",
        "method": "GET",
        "lambda": "get_import",
    },
    "delete_import": {
        "resource": "import_detail",
        "method": "DELETE",
        "lambda": "delete_import",
    },
    "create_publish": {
        "resource": "publish",
        "method": "POST",
        "lambda": "create_publish",
    },
    "get_publish": {
        "resource": "publish",
        "method": "GET",
        "lambda": "get_publish",
    },
}

for function_name, config in lambdas.items():
    with open(f"/opt/code/localstack/{config['zip_file']}", "rb") as f:
        zip_file_bytes = f.read()
    create_kwargs = {
        "FunctionName": function_name,
        "Runtime": "java21",
        "Role": role_arn,
        "Handler": config["handler"],
        "Code": {"ZipFile": zip_file_bytes},
        "Timeout": config.get("timeout", 30),
        "MemorySize": 1024,
        "Architectures": ["x86_64"],
    }
    if "environment" in config:
        create_kwargs["Environment"] = {"Variables": config["environment"]}
    lambda_client.create_function(**create_kwargs)

lambda_client.create_event_source_mapping(
    EventSourceArn=queue_arn,
    FunctionName="jobs_handler",
    BatchSize=1,
)

resource_ids = {}
for name, config in root_resources.items():
    resource_ids[name] = apigateway_client.create_resource(
        restApiId=api_id, parentId=root_id, pathPart=config["path"]
    )["id"]
for name, config in child_resources.items():
    resource_ids[name] = apigateway_client.create_resource(
        restApiId=api_id,
        parentId=resource_ids[config["parent"]],
        pathPart=config["path"],
    )["id"]

for endpoint in endpoints.values():
    apigateway_client.put_method(
        restApiId=api_id,
        resourceId=resource_ids[endpoint["resource"]],
        httpMethod=endpoint["method"],
        authorizationType="NONE",
    )
    apigateway_client.put_integration(
        restApiId=api_id,
        resourceId=resource_ids[endpoint["resource"]],
        httpMethod=endpoint["method"],
        type="AWS_PROXY",
        integrationHttpMethod="POST",
        uri=f"arn:aws:apigateway:{region_name}:lambda:path/2015-03-31/functions/arn:aws:lambda:{region_name}:000000000000:function:{endpoint['lambda']}/invocations",
    )

for function_name in lambdas:
    lambda_client.get_waiter("function_active_v2").wait(FunctionName=function_name)

apigateway_client.create_deployment(restApiId=api_id, stageName="local")
