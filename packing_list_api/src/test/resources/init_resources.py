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
secretsmanager_client = boto3.client(
    "secretsmanager", endpoint_url=endpoint_url, region_name=region_name
)
dynamodb_client = boto3.client(
    "dynamodb", endpoint_url=endpoint_url, region_name=region_name
)

secretsmanager_client.create_secret(
    Name="packing_list_api",
    SecretString=json.dumps({"testuser": "testpass"}),
)

table_name = "packing_list"
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

api_id = apigateway_client.create_rest_api(
    name="packing_list", tags={"_custom_id_": "packing_list"}
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

configs = [
    {
        "function_name": "create_trip_handler",
        "handler_name": "com.jordansimsmith.packinglist.CreateTripHandler",
        "zip_file": "create-trip-handler_deploy.jar",
    },
    {
        "function_name": "find_trips_handler",
        "handler_name": "com.jordansimsmith.packinglist.FindTripsHandler",
        "zip_file": "find-trips-handler_deploy.jar",
    },
    {
        "function_name": "get_trip_handler",
        "handler_name": "com.jordansimsmith.packinglist.GetTripHandler",
        "zip_file": "get-trip-handler_deploy.jar",
    },
    {
        "function_name": "update_trip_handler",
        "handler_name": "com.jordansimsmith.packinglist.UpdateTripHandler",
        "zip_file": "update-trip-handler_deploy.jar",
    },
]

# create /trips resource
trips_resource_id = apigateway_client.create_resource(
    restApiId=api_id, parentId=root_id, pathPart="trips"
)["id"]

# create /trips/{trip_id} resource
trip_resource_id = apigateway_client.create_resource(
    restApiId=api_id, parentId=trips_resource_id, pathPart="{trip_id}"
)["id"]

# create all lambda functions
for config in configs:
    with open(f"/opt/code/localstack/{config['zip_file']}", "rb") as f:
        zip_file_bytes = f.read()

    lambda_client.create_function(
        FunctionName=config["function_name"],
        Runtime="java17",
        Role=role_arn,
        Handler=config["handler_name"],
        Code={"ZipFile": zip_file_bytes},
        Timeout=30,
        MemorySize=1024,
        Architectures=["x86_64"],
    )

# wait for all lambda functions to be active
for config in configs:
    lambda_client.get_waiter("function_active_v2").wait(
        FunctionName=config["function_name"]
    )

# set up API Gateway routes
# POST /trips -> create_trip_handler
apigateway_client.put_method(
    restApiId=api_id,
    resourceId=trips_resource_id,
    httpMethod="POST",
    authorizationType="NONE",
)
apigateway_client.put_integration(
    restApiId=api_id,
    resourceId=trips_resource_id,
    httpMethod="POST",
    type="AWS_PROXY",
    integrationHttpMethod="POST",
    uri=f"arn:aws:apigateway:{region_name}:lambda:path/2015-03-31/functions/arn:aws:lambda:{region_name}:000000000000:function:create_trip_handler/invocations",
)

# GET /trips -> find_trips_handler
apigateway_client.put_method(
    restApiId=api_id,
    resourceId=trips_resource_id,
    httpMethod="GET",
    authorizationType="NONE",
)
apigateway_client.put_integration(
    restApiId=api_id,
    resourceId=trips_resource_id,
    httpMethod="GET",
    type="AWS_PROXY",
    integrationHttpMethod="POST",
    uri=f"arn:aws:apigateway:{region_name}:lambda:path/2015-03-31/functions/arn:aws:lambda:{region_name}:000000000000:function:find_trips_handler/invocations",
)

# GET /trips/{trip_id} -> get_trip_handler
apigateway_client.put_method(
    restApiId=api_id,
    resourceId=trip_resource_id,
    httpMethod="GET",
    authorizationType="NONE",
)
apigateway_client.put_integration(
    restApiId=api_id,
    resourceId=trip_resource_id,
    httpMethod="GET",
    type="AWS_PROXY",
    integrationHttpMethod="POST",
    uri=f"arn:aws:apigateway:{region_name}:lambda:path/2015-03-31/functions/arn:aws:lambda:{region_name}:000000000000:function:get_trip_handler/invocations",
)

# PUT /trips/{trip_id} -> update_trip_handler
apigateway_client.put_method(
    restApiId=api_id,
    resourceId=trip_resource_id,
    httpMethod="PUT",
    authorizationType="NONE",
)
apigateway_client.put_integration(
    restApiId=api_id,
    resourceId=trip_resource_id,
    httpMethod="PUT",
    type="AWS_PROXY",
    integrationHttpMethod="POST",
    uri=f"arn:aws:apigateway:{region_name}:lambda:path/2015-03-31/functions/arn:aws:lambda:{region_name}:000000000000:function:update_trip_handler/invocations",
)

apigateway_client.create_deployment(restApiId=api_id, stageName="local")
