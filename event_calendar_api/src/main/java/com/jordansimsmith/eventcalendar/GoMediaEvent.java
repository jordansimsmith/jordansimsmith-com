package com.jordansimsmith.eventcalendar;

import java.time.Instant;

public record GoMediaEvent(
    String title, String stadiumUrl, String eventUrl, Instant startTime, String eventInfo) {}
