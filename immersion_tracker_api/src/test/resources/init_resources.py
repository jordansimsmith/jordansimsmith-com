import boto3
import json
import os

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
    Name="immersion_tracker_api",
    SecretString=json.dumps(
        {
            "users": [],
            "tvdb_api_key": os.getenv("TVDB_API_KEY", ""),
            "youtube_api_key": os.getenv("YOUTUBE_API_KEY", ""),
            "spotify_client_id": os.getenv("SPOTIFY_CLIENT_ID", ""),
            "spotify_client_secret": os.getenv("SPOTIFY_CLIENT_SECRET", ""),
        }
    ),
)

table_name = "immersion_tracker"
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
    name="immersion_tracker", tags={"_custom_id_": "immersion_tracker"}
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
        "function_name": "get_progress_handler",
        "resource_path_part": "progress",
        "http_method": "GET",
        "handler_name": "com.jordansimsmith.immersiontracker.GetProgressHandler",
        "zip_file": "get-progress-handler_deploy.jar",
    },
    {
        "function_name": "get_shows_handler",
        "resource_path_part": "shows",
        "http_method": "GET",
        "handler_name": "com.jordansimsmith.immersiontracker.GetShowsHandler",
        "zip_file": "get-shows-handler_deploy.jar",
    },
    {
        "function_name": "update_show_handler",
        "resource_path_part": "show",
        "http_method": "PUT",
        "handler_name": "com.jordansimsmith.immersiontracker.UpdateShowHandler",
        "zip_file": "update-show-handler_deploy.jar",
    },
    {
        "function_name": "sync_episodes_handler",
        "resource_path_part": "sync",
        "http_method": "POST",
        "handler_name": "com.jordansimsmith.immersiontracker.SyncEpisodesHandler",
        "zip_file": "sync-episodes-handler_deploy.jar",
    },
    {
        "function_name": "sync_youtube_handler",
        "resource_path_part": "syncyoutube",
        "http_method": "POST",
        "handler_name": "com.jordansimsmith.immersiontracker.SyncYoutubeHandler",
        "zip_file": "sync-youtube-handler_deploy.jar",
    },
    {
        "function_name": "sync_spotify_handler",
        "resource_path_part": "syncspotify",
        "http_method": "POST",
        "handler_name": "com.jordansimsmith.immersiontracker.SyncSpotifyHandler",
        "zip_file": "sync-spotify-handler_deploy.jar",
    },
]

# create all lambda functions and api gateway resources
for config in configs:
    with open(config["zip_file"], "rb") as f:
        zip_file_bytes = f.read()

    function_arn = lambda_client.create_function(
        FunctionName=config["function_name"],
        Runtime="java17",
        Role=role_arn,
        Handler=config["handler_name"],
        Code={"ZipFile": zip_file_bytes},
        Timeout=60,
        MemorySize=512,
    )["FunctionArn"]

    resource_id = apigateway_client.create_resource(
        restApiId=api_id, parentId=root_id, pathPart=config["resource_path_part"]
    )["id"]
    apigateway_client.put_method(
        restApiId=api_id,
        resourceId=resource_id,
        httpMethod=config["http_method"],
        authorizationType="NONE",
    )
    apigateway_client.put_integration(
        restApiId=api_id,
        resourceId=resource_id,
        httpMethod=config["http_method"],
        type="AWS_PROXY",
        integrationHttpMethod="POST",
        uri=f"arn:aws:apigateway:us-east-1:lambda:path/2015-03-31/functions/{function_arn}/invocations",
    )

# wait for all lambda functions to be active
for config in configs:
    lambda_client.get_waiter("function_active_v2").wait(
        FunctionName=config["function_name"]
    )

apigateway_client.create_deployment(restApiId=api_id, stageName="local")
