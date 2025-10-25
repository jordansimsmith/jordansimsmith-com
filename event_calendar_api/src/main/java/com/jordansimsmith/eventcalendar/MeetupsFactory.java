package com.jordansimsmith.eventcalendar;

import java.net.URI;
import java.util.List;

public interface MeetupsFactory {
  record MeetupGroup(URI meetupGroupUrl) {}

  List<MeetupGroup> findMeetupGroups();
}
