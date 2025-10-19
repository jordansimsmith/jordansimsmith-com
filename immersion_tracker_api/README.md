# Immersion tracker service

The immersion tracker service records and monitors media consumption for language learning immersion, tracking watched shows and episodes while providing progress statistics.

## System architecture

```mermaid
graph TD
  A[User/Client] --> B[API Gateway]
  B --> C[Lambda Functions]
  C --> D[DynamoDB]
  C --> E[TVDB API]
  C --> H[YouTube API]
  F[Python Sync Script] --> B
  F --> G[Local File System]
  F --> I[youtube_watched.txt]
  G --> F
  I --> F
```

## Requirements

### Functional requirements

- Track watched TV shows and episodes for language learning immersion
- Track watched YouTube videos for language learning immersion
- Store metadata about shows from TVDB API
- Store metadata about YouTube videos from YouTube API
- Provide progress statistics (total episodes watched, hours watched, etc.)
- Support syncing episodes from local files to the cloud database
- Support syncing YouTube videos from local youtube_watched.txt file
- Allow updating show metadata with TVDB information
- Display progress summaries for all tracked shows and YouTube videos
- Support authentication for secure access

### Technical specifications

- RESTful API for accessing tracker functionality
- Secure data storage with user-based partitioning
- Automatic cleanup of local files after syncing
- Support for common video file formats (.mkv, .mp4)
- Cloud-based storage for persistence across devices
- AWS Lambda-based serverless architecture
- API Gateway with custom domain and HTTPS support

## Implementation details

### Technologies

- AWS Lambda for serverless computing
- Amazon DynamoDB for data storage
- AWS API Gateway for REST API endpoints
- AWS Secrets Manager for credential management
- Java 17 for Lambda implementation
- Python for client-side scripts
- Bazel for building and deployment
- Terraform for infrastructure as code

### Key components

- `AuthHandler`: Handles user authentication
- `GetProgressHandler`: Retrieves progress statistics
- `GetShowsHandler`: Lists tracked shows
- `SyncEpisodesHandler`: Syncs local episodes to the database
- `SyncYoutubeHandler`: Syncs YouTube videos to the database
- `UpdateShowHandler`: Updates show metadata with TVDB information
- `ImmersionTrackerItem`: Data model for DynamoDB items
- `sync_episodes.py`: Client script that scans local files and youtube_watched.txt, calls the Lambda API to sync episodes and YouTube videos, and manages watched files

### Configuration

- DynamoDB table with partition key for user and sort key for item type
- Lambda functions with minimal permissions following least privilege
- API Gateway with custom domain (api.immersion-tracker.jordansimsmith.com)
- Authentication using API Gateway authorizers
- TVDB API integration for show metadata lookup
- YouTube API integration for video metadata lookup

### DynamoDB data model examples

**Episode item:**

```json
{
  "pk": "USER#alice",
  "sk": "EPISODE#show_name#episode_filename",
  "user": "alice",
  "folder_name": "show_name",
  "file_name": "episode_filename",
  "timestamp": 1672531200
}
```

**Show item:**

```json
{
  "pk": "USER#alice",
  "sk": "SHOW#show_name",
  "user": "alice",
  "folder_name": "show_name",
  "tvdb_id": 12345,
  "tvdb_name": "Example Show",
  "tvdb_image": "https://artworks.thetvdb.com/banners/posters/12345-1.jpg"
}
```

**YouTube video item:**

```json
{
  "pk": "USER#alice",
  "sk": "YOUTUBEVIDEO#dQw4w9WgXcQ",
  "user": "alice",
  "youtube_video_id": "dQw4w9WgXcQ",
  "youtube_video_title": "Example Video Title",
  "youtube_channel_id": "UCChannelId123",
  "youtube_video_duration": 253,
  "timestamp": 1672531200
}
```

**YouTube channel item:**

```json
{
  "pk": "USER#alice",
  "sk": "YOUTUBECHANNEL#UCChannelId123",
  "user": "alice",
  "youtube_channel_id": "UCChannelId123",
  "youtube_channel_title": "Example Channel"
}
```
