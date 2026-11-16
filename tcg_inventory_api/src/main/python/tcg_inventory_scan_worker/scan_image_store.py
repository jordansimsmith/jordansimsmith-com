"""S3 access to scan images."""


class ScanImageStore:
    def __init__(self, client, bucket: str):
        self._client = client
        self._bucket = bucket

    def get_image(self, key: str) -> bytes:
        return self._client.get_object(Bucket=self._bucket, Key=key)["Body"].read()
