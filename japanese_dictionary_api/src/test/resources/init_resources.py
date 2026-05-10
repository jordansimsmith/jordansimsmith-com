import json
import time

import boto3

region_name = "ap-southeast-2"
endpoint_url = "http://localhost:4566"

iam_client = boto3.client("iam", endpoint_url=endpoint_url, region_name=region_name)
lambda_client = boto3.client(
    "lambda", endpoint_url=endpoint_url, region_name=region_name
)
apigateway_client = boto3.client(
    "apigateway", endpoint_url=endpoint_url, region_name=region_name
)
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

api_id = apigateway_client.create_rest_api(
    name="japanese_dictionary", tags={"_custom_id_": "japanese_dictionary"}
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
    "auth": {
        "handler": "com.jordansimsmith.japanesedictionary.AuthHandler",
        "zip_file": "auth-handler_deploy.jar",
    },
    "search": {
        "handler": "com.jordansimsmith.japanesedictionary.SearchHandler",
        "zip_file": "search-handler_deploy.jar",
    },
}

root_resources = {
    "search": {"path": "search"},
}

endpoints = {
    "search": {"resource": "search", "method": "GET", "lambda": "search"},
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
        MemorySize=1024,
        Architectures=["x86_64"],
    )
    lambda_client.add_permission(
        FunctionName=function_name,
        StatementId="AllowAPIGatewayInvoke",
        Action="lambda:InvokeFunction",
        Principal="apigateway.amazonaws.com",
    )

authorizer_id = apigateway_client.create_authorizer(
    restApiId=api_id,
    name="japanese_dictionary_authorizer",
    type="REQUEST",
    authorizerUri=(
        f"arn:aws:apigateway:{region_name}:lambda:path/2015-03-31/functions/"
        f"arn:aws:lambda:{region_name}:000000000000:function:auth/invocations"
    ),
    identitySource="method.request.header.Authorization",
    authorizerResultTtlInSeconds=0,
)["id"]

apigateway_client.put_gateway_response(
    restApiId=api_id,
    responseType="UNAUTHORIZED",
    statusCode="401",
    responseParameters={
        "gatewayresponse.header.WWW-Authenticate": "'Basic'",
    },
    responseTemplates={
        "application/json": '{"message":$context.error.messageString}',
    },
)

resource_ids = {}
for name, config in root_resources.items():
    resource_ids[name] = apigateway_client.create_resource(
        restApiId=api_id, parentId=root_id, pathPart=config["path"]
    )["id"]

for endpoint in endpoints.values():
    apigateway_client.put_method(
        restApiId=api_id,
        resourceId=resource_ids[endpoint["resource"]],
        httpMethod=endpoint["method"],
        authorizationType="CUSTOM",
        authorizerId=authorizer_id,
    )
    apigateway_client.put_integration(
        restApiId=api_id,
        resourceId=resource_ids[endpoint["resource"]],
        httpMethod=endpoint["method"],
        type="AWS_PROXY",
        integrationHttpMethod="POST",
        uri=(
            f"arn:aws:apigateway:{region_name}:lambda:path/2015-03-31/functions/"
            f"arn:aws:lambda:{region_name}:000000000000:function:{endpoint['lambda']}/invocations"
        ),
    )

for function_name in lambdas:
    lambda_client.get_waiter("function_active_v2").wait(FunctionName=function_name)

apigateway_client.create_deployment(restApiId=api_id, stageName="local")
