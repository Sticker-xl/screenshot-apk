package com.example.screenrec;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.view.Surface;

import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;

/** Screen + optional audio -> H.264 / AAC -> MP4. */
final class Recorder {

    static final int AUDIO_NONE = 0;
    static final int AUDIO_MIC = 1;
    static final int AUDIO_INTERNAL = 2;
    static final int AUDIO_BOTH = 3;

    private static final int RATE = 44100;
    private static final int CHUNK = 1024; // audio frames per chunk (stereo = 2 shorts per frame)

    private static final class Pending {
        final boolean video;
        final ByteBuffer data;
        final MediaCodec.BufferInfo info;

        Pending(boolean video, ByteBuffer data, MediaCodec.BufferInfo info) {
            this.video = video;
            this.data = data;
            this.info = info;
        }
    }

    private final MediaProjection projection;
    private final boolean useMic;
    private final boolean useInternal;

    private MediaCodec videoEnc;
    private MediaCodec audioEnc;
    private MediaMuxer muxer;
    private Surface surface;
    private VirtualDisplay display;
    private AudioRecord micRec;
    private AudioRecord internalRec;
    private Thread videoThread;
    private Thread audioThread;
    private volatile boolean running;
    private boolean audioEos;
    private long startUs;
    private long videoShift = Long.MIN_VALUE;

    /** Last problem seen on a worker thread; shown to the user if nothing could be saved. */
    volatile String error;

    // Muxer state, guarded by "this".
    private int videoTrack = -1;
    private int audioTrack = -1;
    private int videoSamples;
    private boolean hasAudio;
    private boolean muxerStarted;
    private boolean finished;
    private final ArrayList<Pending> pending = new ArrayList<>();

    Recorder(MediaProjection projection, int audioMode) {
        this.projection = projection;
        this.useMic = audioMode == AUDIO_MIC || audioMode == AUDIO_BOTH;
        this.useInternal = audioMode == AUDIO_INTERNAL || audioMode == AUDIO_BOTH;
    }

    void start(FileDescriptor fd, int screenW, int screenH, int dpi) throws Exception {
        // ---- video encoder, at a size this phone's encoder supports ----
        String avc = MediaFormat.MIMETYPE_VIDEO_AVC;
        videoEnc = MediaCodec.createEncoderByType(avc);
        MediaCodecInfo.VideoCapabilities caps =
                videoEnc.getCodecInfo().getCapabilitiesForType(avc).getVideoCapabilities();
        int wa = Math.max(2, caps.getWidthAlignment());
        int ha = Math.max(2, caps.getHeightAlignment());
        float scale = Math.min(1f, 1080f / Math.min(screenW, screenH));
        int w = screenW;
        int h = screenH;
        for (int i = 0; i < 8; i++) {
            w = Math.max(wa, (int) (screenW * scale) / wa * wa);
            h = Math.max(ha, (int) (screenH * scale) / ha * ha);
            if (caps.areSizeAndRateSupported(w, h, 30)) break;
            scale *= 0.75f;
        }
        MediaFormat vf = MediaFormat.createVideoFormat(avc, w, h);
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        vf.setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000);
        vf.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        vf.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000L);
        videoEnc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        surface = videoEnc.createInputSurface();
        videoEnc.start();

        muxer = new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // ---- audio: microphone and/or internal (playback capture) ----
        if (useMic || useInternal) {
            int stereoBuf = Math.max(AudioRecord.getMinBufferSize(RATE,
                    AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT), CHUNK * 4) * 4;
            int monoBuf = Math.max(AudioRecord.getMinBufferSize(RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), CHUNK * 2) * 4;
            if (useInternal) {
                AudioPlaybackCaptureConfiguration cfg =
                        new AudioPlaybackCaptureConfiguration.Builder(projection)
                                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                                .build();
                internalRec = new AudioRecord.Builder()
                        .setAudioFormat(pcm(AudioFormat.CHANNEL_IN_STEREO))
                        .setBufferSizeInBytes(stereoBuf)
                        .setAudioPlaybackCaptureConfig(cfg)
                        .build();
            }
            if (useMic) {
                micRec = new AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(pcm(AudioFormat.CHANNEL_IN_MONO))
                        .setBufferSizeInBytes(monoBuf)
                        .build();
            }
            if ((internalRec != null && internalRec.getState() != AudioRecord.STATE_INITIALIZED)
                    || (micRec != null && micRec.getState() != AudioRecord.STATE_INITIALIZED)) {
                throw new IllegalStateException("audio input not available");
            }

            MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2);
            af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            af.setInteger(MediaFormat.KEY_BIT_RATE, 128_000);
            af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            audioEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            audioEnc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioEnc.start();
            hasAudio = true;
        }

        // ---- go ----
        running = true;
        startUs = System.nanoTime() / 1000;
        if (internalRec != null) internalRec.startRecording();
        if (micRec != null) micRec.startRecording();
        display = projection.createVirtualDisplay("ScreenRecorder", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null);
        videoThread = new Thread(this::videoLoop, "rec-video");
        videoThread.setDaemon(true);
        videoThread.start();
        if (hasAudio) {
            audioThread = new Thread(this::audioLoop, "rec-audio");
            audioThread.setDaemon(true);
            audioThread.start();
        }
    }

    /** Stops everything and finishes the file. Returns true if a playable video was written. */
    boolean stop() {
        running = false;
        join(audioThread);
        if (display != null) {
            display.release();
            display = null;
        }
        if (videoThread != null && videoEnc != null) {
            try {
                videoEnc.signalEndOfInputStream();
            } catch (Exception ignored) {
            }
        }
        join(videoThread);

        boolean ok = false;
        synchronized (this) {
            finished = true;
            pending.clear();
            if (muxer != null) {
                try {
                    if (muxerStarted) {
                        muxer.stop();
                        ok = videoSamples > 0;
                    }
                } catch (Exception e) {
                    if (error == null) error = "muxer: " + e;
                }
                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
                muxer = null;
            }
        }

        close(videoEnc);
        close(audioEnc);
        close(micRec);
        close(internalRec);
        if (surface != null) surface.release();
        videoEnc = null;
        audioEnc = null;
        micRec = null;
        internalRec = null;
        surface = null;
        return ok;
    }

    // ------------------------------------------------------------------ video

    private void videoLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            boolean eos = false;
            while (!eos) {
                int i = videoEnc.dequeueOutputBuffer(info, 10_000);
                if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    onFormat(true, videoEnc.getOutputFormat());
                } else if (i >= 0) {
                    ByteBuffer buf = videoEnc.getOutputBuffer(i);
                    eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (buf != null && info.size > 0
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        info.presentationTimeUs = videoPts(info.presentationTimeUs);
                        write(true, buf, info);
                    }
                    videoEnc.releaseOutputBuffer(i, false);
                }
            }
        } catch (Exception e) {
            error = "video: " + e;
        }
    }

    /** Frame timestamps normally share System.nanoTime()'s clock; if so, keep them relative to
     *  recording start so they line up with the audio. Otherwise just start at zero. */
    private long videoPts(long codecUs) {
        if (videoShift == Long.MIN_VALUE) {
            long d = codecUs - startUs;
            videoShift = (d > -500_000L && d < 10_000_000L) ? startUs : codecUs;
        }
        return Math.max(0L, codecUs - videoShift);
    }

    // ------------------------------------------------------------------ audio

    private void audioLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        short[] mix = new short[CHUNK * 2];
        short[] mono = new short[CHUNK];
        long frames = 0;
        try {
            while (running && readChunk(mix, mono)) {
                queueAudio(mix, mix.length, frames, false, info);
                frames += CHUNK;
            }
            queueAudio(null, 0, frames, true, info);
            for (int i = 0; i < 100 && !audioEos; i++) drainAudio(info, 20_000);
        } catch (Exception e) {
            error = "audio: " + e;
        } finally {
            dropAudio();
        }
    }

    /** Fills mix with CHUNK stereo frames: internal audio + (mono) mic added to both channels. */
    private boolean readChunk(short[] mix, short[] mono) {
        if (internalRec != null) {
            if (!readFully(internalRec, mix, CHUNK * 2)) return false;
        } else {
            Arrays.fill(mix, (short) 0);
        }
        if (micRec != null) {
            if (!readFully(micRec, mono, CHUNK)) return false;
            for (int i = 0; i < CHUNK; i++) {
                mix[2 * i] = clip(mix[2 * i] + mono[i]);
                mix[2 * i + 1] = clip(mix[2 * i + 1] + mono[i]);
            }
        }
        return true;
    }

    private static boolean readFully(AudioRecord rec, short[] buf, int count) {
        int got = 0;
        while (got < count) {
            int n = rec.read(buf, got, count - got);
            if (n <= 0) return false;
            got += n;
        }
        return true;
    }

    private static short clip(int v) {
        return (short) Math.max(-32768, Math.min(32767, v));
    }

    private void queueAudio(short[] pcm, int shorts, long frames, boolean eos,
                            MediaCodec.BufferInfo info) {
        int idx = -1;
        for (int i = 0; i < 100 && idx < 0; i++) {
            idx = audioEnc.dequeueInputBuffer(10_000);
            if (idx < 0) drainAudio(info, 0);
        }
        if (idx < 0) return; // encoder is stuck, skip this chunk
        ByteBuffer input = audioEnc.getInputBuffer(idx);
        input.clear();
        int bytes = 0;
        if (pcm != null) {
            input.order(ByteOrder.LITTLE_ENDIAN);
            input.asShortBuffer().put(pcm, 0, shorts);
            bytes = shorts * 2;
        }
        audioEnc.queueInputBuffer(idx, 0, bytes, frames * 1_000_000L / RATE,
                eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
        drainAudio(info, 0);
    }

    private void drainAudio(MediaCodec.BufferInfo info, long timeoutUs) {
        while (true) {
            int i = audioEnc.dequeueOutputBuffer(info, timeoutUs);
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                onFormat(false, audioEnc.getOutputFormat());
            } else if (i >= 0) {
                ByteBuffer buf = audioEnc.getOutputBuffer(i);
                if (buf != null && info.size > 0
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    write(false, buf, info);
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) audioEos = true;
                audioEnc.releaseOutputBuffer(i, false);
                if (audioEos) return;
            } else {
                return;
            }
        }
    }

    // ------------------------------------------------------------------ muxer

    private synchronized void onFormat(boolean video, MediaFormat format) {
        if (finished) return;
        if (video) {
            videoTrack = muxer.addTrack(format);
        } else if (hasAudio) {
            audioTrack = muxer.addTrack(format);
        }
        startIfReady();
    }

    /** Caller holds the lock. Starts the muxer once every expected track is known. */
    private void startIfReady() {
        if (finished || muxerStarted || videoTrack < 0 || (hasAudio && audioTrack < 0)) return;
        muxer.start();
        muxerStarted = true;
        for (Pending p : pending) writeNow(p.video, p.data, p.info);
        pending.clear();
    }

    /** Audio gave up (or ended): don't wait for it any more. */
    private synchronized void dropAudio() {
        if (!hasAudio) return;
        hasAudio = false;
        if (audioTrack < 0) {
            pending.removeIf(p -> !p.video);
            startIfReady();
        }
    }

    private synchronized void write(boolean video, ByteBuffer buf, MediaCodec.BufferInfo info) {
        if (finished || (!video && !hasAudio)) return;
        if (muxerStarted) {
            writeNow(video, buf, info);
            return;
        }
        // Muxer not started yet (waiting for the other track): keep a copy.
        ByteBuffer copy = ByteBuffer.allocate(info.size);
        copy.put(buf);
        copy.flip();
        MediaCodec.BufferInfo ci = new MediaCodec.BufferInfo();
        ci.set(0, info.size, info.presentationTimeUs, info.flags);
        pending.add(new Pending(video, copy, ci));
        if (pending.size() > 600) dropAudio();
    }

    private void writeNow(boolean video, ByteBuffer buf, MediaCodec.BufferInfo info) {
        try {
            muxer.writeSampleData(video ? videoTrack : audioTrack, buf, info);
            if (video) videoSamples++;
        } catch (RuntimeException e) {
            error = (video ? "video write: " : "audio write: ") + e;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static AudioFormat pcm(int channelMask) {
        return new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(channelMask)
                .build();
    }

    private static void join(Thread t) {
        if (t == null) return;
        try {
            t.join(2500);
        } catch (InterruptedException ignored) {
        }
    }

    private static void close(MediaCodec c) {
        if (c == null) return;
        try {
            c.stop();
        } catch (Exception ignored) {
        }
        try {
            c.release();
        } catch (Exception ignored) {
        }
    }

    private static void close(AudioRecord r) {
        if (r == null) return;
        try {
            r.stop();
        } catch (Exception ignored) {
        }
        try {
            r.release();
        } catch (Exception ignored) {
        }
    }
}
