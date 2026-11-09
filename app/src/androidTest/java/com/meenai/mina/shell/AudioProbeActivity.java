package com.wangzan101.mina.shell;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;

/**
 *The sounding probe page:After opening, a silent sound is played continuously in a loop(The player status is "Playing",Can't hear),Stop when turned off.
 *The rest are the same as {@link ShellProbeActivity}(Submit screens and tasks to testing). At the end of the task, "the app that is making sounds will be turned off by default" and use this to test.
 */
public class AudioProbeActivity extends ShellProbeActivity {
    private static final int RATE = 8000;
    private AudioTrack track;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        short[] silence = new short[RATE / 2];
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(silence.length * 2)
                .build();
        track.write(silence, 0, silence.length);
        track.setLoopPoints(0, silence.length, -1);
        track.play();
    }

    @Override
    protected void onDestroy() {
        if (track != null) {
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
                //Playback was turned off before it started
            }
            track.release();
            track = null;
        }
        super.onDestroy();
    }
}
