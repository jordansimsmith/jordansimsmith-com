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
    Name="book_tracker_api",
    SecretString=json.dumps({"users": [{"user": "alice", "password": "password"}]}),
)

table_name = "book_tracker"
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
    name="book_tracker", tags={"_custom_id_": "book_tracker"}
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
        "function_name": "create_book_handler",
        "handler_name": "com.jordansimsmith.booktracker.CreateBookHandler",
        "zip_file": "create-book-handler_deploy.jar",
    },
    {
        "function_name": "find_books_handler",
        "handler_name": "com.jordansimsmith.booktracker.FindBooksHandler",
        "zip_file": "find-books-handler_deploy.jar",
    },
    {
        "function_name": "get_book_handler",
        "handler_name": "com.jordansimsmith.booktracker.GetBookHandler",
        "zip_file": "get-book-handler_deploy.jar",
    },
    {
        "function_name": "update_book_handler",
        "handler_name": "com.jordansimsmith.booktracker.UpdateBookHandler",
        "zip_file": "update-book-handler_deploy.jar",
    },
    {
        "function_name": "delete_book_handler",
        "handler_name": "com.jordansimsmith.booktracker.DeleteBookHandler",
        "zip_file": "delete-book-handler_deploy.jar",
    },
]

# create /books resource
books_resource_id = apigateway_client.create_resource(
    restApiId=api_id, parentId=root_id, pathPart="books"
)["id"]

# create /books/{open_library_work_id} resource
book_resource_id = apigateway_client.create_resource(
    restApiId=api_id, parentId=books_resource_id, pathPart="{open_library_work_id}"
)["id"]

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
    )

# wait for all lambda functions to be active
for config in configs:
    lambda_client.get_waiter("function_active_v2").wait(
        FunctionName=config["function_name"]
    )

# POST /books -> create_book_handler
apigateway_client.put_method(
    restApiId=api_id,
    resourceId=books_resource_id,
    httpMethod="POST",
    authorizationType="NONE",
)
apigateway_client.put_integration(
    restApiId=api_id,
    resourceId=books_resource_id,
    httpMethod="POST",
    type="AWS_PROXY",
    integrationHttpMethod="POST",
    uri=f"arn:aws:apigateway:{region_name}:lambda:path/2015-03-31/functions/arn:aws:lambda:{region_name}:000000000000:function:create_book_handler/invocations",
)

# GET /books -> find_books_handler
apigateway_client.put_method(
    restApiId=api_id,
    resourceId=books_resource_id,
    httpMethod="GET",
    authorizationType="NONE",
)
apigateway_client.put_integration(
    restApiId=api_id,
    resourceId=books_resource_id,
    httpMethod="GET",
    type="AWS_PROXY",
    integrationHttpMethod="POST",
    uri=f"arn:aws:apigateway:{region_name}:lambda:path/2015-03-31/functions/arn:aws:lambda:{region_name}:000000000000:function:find_books_handler/invocations",
)

# GET /books/{open_library_work_id} -> get_book_handler
apigateway_client.put_method(
    restApiId=api_id,
    resourceId=book_resource_id,
    httpMethod="GET",
    authorizationType="NONE",
)
apigateway_client.put_integration(
    restApiId=api_id,
    resourceId=book_resource_id,
    httpMethod="GET",
    type="AWS_PROXY",
    integrationHttpMethod="POST",
    uri=f"arn:aws:apigateway:{region_name}:lambda:path/2015-03-31/functions/arn:aws:lambda:{region_name}:000000000000:function:get_book_handler/invocations",
)

# PUT /books/{open_library_work_id} -> update_book_handler
apigateway_client.put_method(
    restApiId=api_id,
    resourceId=book_resource_id,
    httpMethod="PUT",
    authorizationType="NONE",
)
apigateway_client.put_integration(
    restApiId=api_id,
    resourceId=book_resource_id,
    httpMethod="PUT",
    type="AWS_PROXY",
    integrationHttpMethod="POST",
    uri=f"arn:aws:apigateway:{region_name}:lambda:path/2015-03-31/functions/arn:aws:lambda:{region_name}:000000000000:function:update_book_handler/invocations",
)

# DELETE /books/{open_library_work_id} -> delete_book_handler
apigateway_client.put_method(
    restApiId=api_id,
    resourceId=book_resource_id,
    httpMethod="DELETE",
    authorizationType="NONE",
)
apigateway_client.put_integration(
    restApiId=api_id,
    resourceId=book_resource_id,
    httpMethod="DELETE",
    type="AWS_PROXY",
    integrationHttpMethod="POST",
    uri=f"arn:aws:apigateway:{region_name}:lambda:path/2015-03-31/functions/arn:aws:lambda:{region_name}:000000000000:function:delete_book_handler/invocations",
)

apigateway_client.create_deployment(restApiId=api_id, stageName="local")
