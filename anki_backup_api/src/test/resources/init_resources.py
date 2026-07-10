import boto3
import json

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
s3_client = boto3.client("s3", endpoint_url=endpoint_url, region_name=region_name)

s3_client.create_bucket(
    Bucket="anki-backup.jordansimsmith.com",
    CreateBucketConfiguration={"LocationConstraint": region_name},
)

table_name = "anki_backup"
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

api_id = apigateway_client.create_rest_api(
    name="anki_backup", tags={"_custom_id_": "anki_backup"}
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

lambdas = {
    "create_backup": {
        "handler": "com.jordansimsmith.ankibackup.CreateBackupHandler",
        "zip_file": "create-backup-handler_deploy.jar",
    },
    "update_backup": {
        "handler": "com.jordansimsmith.ankibackup.UpdateBackupHandler",
        "zip_file": "update-backup-handler_deploy.jar",
    },
    "find_backups": {
        "handler": "com.jordansimsmith.ankibackup.FindBackupsHandler",
        "zip_file": "find-backups-handler_deploy.jar",
    },
    "get_backup": {
        "handler": "com.jordansimsmith.ankibackup.GetBackupHandler",
        "zip_file": "get-backup-handler_deploy.jar",
    },
}

root_resources = {
    "backups": {"path": "backups"},
}

child_resources = {
    "backup": {"path": "{backup_id}", "parent": "backups"},
}

endpoints = {
    "create_backup": {
        "resource": "backups",
        "method": "POST",
        "lambda": "create_backup",
    },
    "find_backups": {
        "resource": "backups",
        "method": "GET",
        "lambda": "find_backups",
    },
    "update_backup": {
        "resource": "backup",
        "method": "PUT",
        "lambda": "update_backup",
    },
    "get_backup": {"resource": "backup", "method": "GET", "lambda": "get_backup"},
}

for function_name, config in lambdas.items():
    with open(f"/opt/code/localstack/{config['zip_file']}", "rb") as f:
        zip_file_bytes = f.read()
    lambda_client.create_function(
        FunctionName=function_name,
        Runtime="java21",
        Role=role_arn,
        Handler=config["handler"],
        Code={"ZipFile": zip_file_bytes},
        Timeout=30,
        MemorySize=512,
        Architectures=["x86_64"],
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
