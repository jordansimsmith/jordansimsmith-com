package com.jordansimsmith.booktracker;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import javax.annotation.Nullable;

public record Book(
    @JsonProperty("open_library_work_id") String openLibraryWorkId,
    @JsonProperty("title") String title,
    @JsonProperty("authors") List<String> authors,
    @Nullable @JsonProperty("cover_url") String coverUrl,
    @Nullable @JsonProperty("page_count") Integer pageCount,
    @Nullable @JsonProperty("publication_year") Integer publicationYear,
    @JsonProperty("finished_date") String finishedDate,
    @JsonProperty("created_at") long createdAt,
    @JsonProperty("updated_at") long updatedAt) {}
