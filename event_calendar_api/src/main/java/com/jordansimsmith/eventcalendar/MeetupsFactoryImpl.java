package com.jordansimsmith.eventcalendar;

import java.net.URI;
import java.util.List;

public class MeetupsFactoryImpl implements MeetupsFactory {
  private static final MeetupGroup ENKAI =
      new MeetupGroup(
          URI.create("https://www.meetup.com/auckland-japanese-english-exchange-enkai-縁会"));

  @Override
  public List<MeetupGroup> findMeetupGroups() {
    return List.of(ENKAI);
  }
}
