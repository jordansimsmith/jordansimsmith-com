package com.jordansimsmith.eventcalendar;

import java.net.URI;
import java.util.List;

public class MeetupsFactoryImpl implements MeetupsFactory {
  private static final MeetupGroup ENKAI =
      new MeetupGroup(
          URI.create("https://www.meetup.com/auckland-japanese-english-exchange-enkai-縁会"));
  private static final MeetupGroup SHUUKAI =
      new MeetupGroup(URI.create("https://www.meetup.com/japanese-english-meetup"));

  @Override
  public List<MeetupGroup> findMeetupGroups() {
    return List.of(ENKAI, SHUUKAI);
  }
}
