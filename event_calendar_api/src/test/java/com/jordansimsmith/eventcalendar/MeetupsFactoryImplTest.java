package com.jordansimsmith.eventcalendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

public class MeetupsFactoryImplTest {
  @Test
  void findMeetupGroupsShouldReturnConfiguredGroupsWhenFactoryIsCreated() {
    // arrange
    var factory = new MeetupsFactoryImpl();

    // act
    var meetupGroups = factory.findMeetupGroups();

    // assert
    assertThat(meetupGroups).hasSize(2);
    assertThat(meetupGroups.stream().map(MeetupsFactory.MeetupGroup::meetupGroupUrl))
        .containsExactly(
            URI.create("https://www.meetup.com/auckland-japanese-english-exchange-enkai-縁会"),
            URI.create("https://www.meetup.com/japanese-english-meetup"));
  }
}
