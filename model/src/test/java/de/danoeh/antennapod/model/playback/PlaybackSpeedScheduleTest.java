package de.danoeh.antennapod.model.playback;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class PlaybackSpeedScheduleTest {

    private static boolean isActive(int fromHour, int toHour, int hourOfDay) {
        return new PlaybackSpeedSchedule(fromHour, toHour, 1.5f).isActiveAt(hourOfDay);
    }

    @Test
    public void activeInsideRangeOfSameDay() {
        assertThat(isActive(7, 9, 6), is(equalTo(false)));
        assertThat(isActive(7, 9, 7), is(equalTo(true)));
        assertThat(isActive(7, 9, 8), is(equalTo(true)));
        assertThat(isActive(7, 9, 9), is(equalTo(false)));
    }

    @Test
    public void activeInsideRangeAcrossMidnight() {
        assertThat(isActive(22, 6, 21), is(equalTo(false)));
        assertThat(isActive(22, 6, 22), is(equalTo(true)));
        assertThat(isActive(22, 6, 23), is(equalTo(true)));
        assertThat(isActive(22, 6, 0), is(equalTo(true)));
        assertThat(isActive(22, 6, 5), is(equalTo(true)));
        assertThat(isActive(22, 6, 6), is(equalTo(false)));
    }

    @Test
    public void activeForWholeDayIfStartEqualsEnd() {
        assertThat(isActive(22, 22, 0), is(equalTo(true)));
        assertThat(isActive(22, 22, 12), is(equalTo(true)));
        assertThat(isActive(22, 22, 23), is(equalTo(true)));
    }

    @Test
    public void exposesConfiguredValues() {
        PlaybackSpeedSchedule schedule = new PlaybackSpeedSchedule(22, 6, 0.8f);

        assertThat(schedule.getFromHour(), is(equalTo(22)));
        assertThat(schedule.getToHour(), is(equalTo(6)));
        assertThat(schedule.getSpeed(), is(equalTo(0.8f)));
    }
}
