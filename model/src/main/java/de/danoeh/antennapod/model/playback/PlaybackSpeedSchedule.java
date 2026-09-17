package de.danoeh.antennapod.model.playback;

public class PlaybackSpeedSchedule {
    private final int fromHour;
    private final int toHour;
    private final float speed;

    public PlaybackSpeedSchedule(int fromHour, int toHour, float speed) {
        this.fromHour = fromHour;
        this.toHour = toHour;
        this.speed = speed;
    }

    public int getFromHour() {
        return fromHour;
    }

    public int getToHour() {
        return toHour;
    }

    public float getSpeed() {
        return speed;
    }

    public boolean isActiveAt(int hourOfDay) {
        if (fromHour == toHour) {
            return true;
        }
        if (fromHour < toHour) {
            return hourOfDay >= fromHour && hourOfDay < toHour;
        }
        return hourOfDay >= fromHour || hourOfDay < toHour;
    }
}
