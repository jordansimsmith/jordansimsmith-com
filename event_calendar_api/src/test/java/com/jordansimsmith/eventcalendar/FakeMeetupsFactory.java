package com.jordansimsmith.eventcalendar;

import java.util.ArrayList;
import java.util.List;

public class FakeMeetupsFactory implements MeetupsFactory {
  private final List<MeetupGroup> meetupGroups = new ArrayList<>();

  @Override
  public List<MeetupGroup> findMeetupGroups() {
    return meetupGroups;
  }

  public void addMeetupGroup(MeetupGroup group) {
    meetupGroups.add(group);
  }

  public void reset() {
    meetupGroups.clear();
  }
}
