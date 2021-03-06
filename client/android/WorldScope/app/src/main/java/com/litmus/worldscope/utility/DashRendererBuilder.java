package com.litmus.worldscope.utility;

import android.content.Context;
import android.media.MediaCodec;
import android.os.Handler;
import android.util.Log;

import com.google.android.exoplayer.DefaultLoadControl;
import com.google.android.exoplayer.LoadControl;
import com.google.android.exoplayer.MediaCodecAudioTrackRenderer;
import com.google.android.exoplayer.MediaCodecVideoTrackRenderer;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.chunk.ChunkSampleSource;
import com.google.android.exoplayer.chunk.ChunkSource;
import com.google.android.exoplayer.chunk.FormatEvaluator;
import com.google.android.exoplayer.dash.DashChunkSource;
import com.google.android.exoplayer.dash.DefaultDashTrackSelector;
import com.google.android.exoplayer.dash.mpd.AdaptationSet;
import com.google.android.exoplayer.dash.mpd.MediaPresentationDescription;
import com.google.android.exoplayer.dash.mpd.MediaPresentationDescriptionParser;
import com.google.android.exoplayer.dash.mpd.Period;
import com.google.android.exoplayer.dash.mpd.UtcTimingElement;
import com.google.android.exoplayer.dash.mpd.UtcTimingElementResolver;
import com.google.android.exoplayer.drm.MediaDrmCallback;
import com.google.android.exoplayer.drm.StreamingDrmSessionManager;
import com.google.android.exoplayer.drm.UnsupportedDrmException;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DefaultAllocator;
import com.google.android.exoplayer.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer.upstream.DefaultUriDataSource;
import com.google.android.exoplayer.util.ManifestFetcher;
import com.google.android.exoplayer.util.Util;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

public class DashRendererBuilder implements ManifestFetcher.ManifestCallback<MediaPresentationDescription>, UtcTimingElementResolver.UtcTimingCallback {

    private static final String TAG = "DashRendererBuilder";

    // Variables for constructing builder
    private final ManifestFetcher<MediaPresentationDescription> manifestFetcher;
    private MediaPresentationDescription manifest;
    private final DefaultUriDataSource manifestDataSource;
    private final LitmusPlayer player;
    private final Context context;
    private final String userAgent;
    private final MediaDrmCallback drmCallback;
    private boolean requireManifest;
    private Timer timer;

    private MediaCodecVideoTrackRenderer videoRenderer;
    private MediaCodecAudioTrackRenderer audioRenderer;

    // Variables for video
    private static final int BUFFER_SEGMENT_SIZE = 64 * 1024;
    private static final int LIVE_EDGE_LATENCY_MS = 30000;
    private static final int VIDEO_BUFFER_SEGMENTS = 200;
    private static final int AUDIO_BUFFER_SEGMENTS = 54;

    // To be reset
    private long elapsedRealtimeOffset;

    // Variables for building renderers
    private static final LoadControl loadControl = new DefaultLoadControl(new DefaultAllocator(BUFFER_SEGMENT_SIZE));
    private static final DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();


    public DashRendererBuilder(LitmusPlayer player, Context context, String mpdLink, String userAgent, MediaDrmCallback drmCallback) {

        Log.d(TAG, "DashRendererBuilder created");
        Log.d(TAG, "mpdLink: " + mpdLink);
        this.player = player;
        this.context = context;
        this.userAgent = userAgent;
        this.drmCallback = drmCallback;

        requireManifest = true;

        MediaPresentationDescriptionParser parser = new MediaPresentationDescriptionParser();
        manifestDataSource = new DefaultUriDataSource(context, userAgent);
        manifestFetcher = new ManifestFetcher<>(mpdLink, manifestDataSource, parser);
        // Get an initial manifest
        manifestFetcher.singleLoad(player.getMainHandler().getLooper(), this);

    }

    // Function to build video renderer
    private void buildVideoRenderer(Context context, StreamingDrmSessionManager drmSessionManager) {
        Handler mainHandler = player.getMainHandler();
        DataSource videoDataSource = new DefaultUriDataSource(context, bandwidthMeter, userAgent);
        ChunkSource videoChunkSource = new DashChunkSource(manifestFetcher,
                DefaultDashTrackSelector.newVideoInstance(context, true, false), videoDataSource,
                new FormatEvaluator.AdaptiveEvaluator(bandwidthMeter), LIVE_EDGE_LATENCY_MS, elapsedRealtimeOffset, null, null);
        ChunkSampleSource videoSampleSource = new ChunkSampleSource(videoChunkSource, loadControl,
                VIDEO_BUFFER_SEGMENTS * BUFFER_SEGMENT_SIZE);
        videoRenderer = new MediaCodecVideoTrackRenderer(context, videoSampleSource,
                MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT, 5000, drmSessionManager, true,
                mainHandler, player, 50);

    }

    // Function to build audio renderer
    private void buildAudioRenderer(Context context) {
        DataSource audioDataSource = new DefaultUriDataSource(context, bandwidthMeter, userAgent);
        ChunkSource audioChunkSource = new DashChunkSource(manifestFetcher,
                DefaultDashTrackSelector.newAudioInstance(), audioDataSource, null, LIVE_EDGE_LATENCY_MS,
                elapsedRealtimeOffset, null, null);
        ChunkSampleSource audioSampleSource = new ChunkSampleSource(audioChunkSource,
                loadControl, AUDIO_BUFFER_SEGMENTS * BUFFER_SEGMENT_SIZE);
        audioRenderer = new MediaCodecAudioTrackRenderer(audioSampleSource);
    }

    // Function to build both renderers
    private void buildRenderers() {

        Log.d(TAG, "Building renderers");

        Period period = manifest.getPeriod(0);

        boolean hasContentProtection = false;
        for (int i = 0; i < period.adaptationSets.size(); i++) {
            AdaptationSet adaptationSet = period.adaptationSets.get(i);
            if (adaptationSet.type != AdaptationSet.TYPE_UNKNOWN) {
                hasContentProtection |= adaptationSet.hasContentProtection();
            }
        }

        // Check drm support if necessary
        StreamingDrmSessionManager drmSessionManager = null;
        if (hasContentProtection) {
            if (Util.SDK_INT < 18) {
                Log.e(TAG, "RENDERER ERROR IN buildRenderers");
                return;
            }
            try {
                drmSessionManager = StreamingDrmSessionManager.newWidevineInstance(
                        player.getPlaybackLooper(), drmCallback, null, player.getMainHandler(), player);
            } catch (UnsupportedDrmException e) {
                Log.e(TAG, "RENDERER ERROR IN buildRenderers");
                return;
            }
        }

        buildVideoRenderer(this.context, drmSessionManager);
        buildAudioRenderer(this.context);
        // Pass the renderers back into the player
        TrackRenderer[] renderers = new TrackRenderer[LitmusPlayer.RENDERER_COUNT];
        renderers[LitmusPlayer.TYPE_VIDEO] = videoRenderer;
        renderers[LitmusPlayer.TYPE_AUDIO] = audioRenderer;
        player.onRenderers(renderers);
        Log.d(TAG, "Renderer ready to push");
        player.readyToPushSurface(LitmusPlayer.RENDERER_READY);
    }

    public MediaCodecVideoTrackRenderer getVideoRenderer() {
        return this.videoRenderer;
    }

    public MediaCodecAudioTrackRenderer getAudioRenderer() {
        return this.audioRenderer;
    }

    // Implement for ManifestCallback
    @Override
    public void onSingleManifest(MediaPresentationDescription manifest) {
        Log.d(TAG, "Received manifest");

        requireManifest = false;

        this.manifest = manifest;
        if (manifest.dynamic && manifest.utcTiming != null) {
            UtcTimingElementResolver.resolveTimingElement(manifestDataSource, manifest.utcTiming,
                    manifestFetcher.getManifestLoadCompleteTimestamp(), this);
        } else {
            buildRenderers();
        }
    }

    @Override
    public void onSingleManifestError(IOException e) {
        if(requireManifest && timer == null) {
            Log.d(TAG, "Trying to get manifest every three seconds");
            timer = new Timer();
            timer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    Log.d(TAG, "Getting manifest");
                    manifestFetcher.singleLoad(player.getMainHandler().getLooper(), DashRendererBuilder.this);
                }
            }, 0, 5000);
        } else if (!requireManifest) {
            Log.d(TAG, "Cancel timer");
            if(timer != null) {
                timer.cancel();
                timer = null;
            }
        }
        // Set it to true if manifest is required
        requireManifest = true;
    }

    // Implement for UTCTimingCallBack
    @Override
    public void onTimestampError(UtcTimingElement utcTiming, IOException e) {
        Log.e(TAG, "Failed to resolve UtcTiming element [" + utcTiming + "]", e);
        // Be optimistic and continue in the hope that the device clock is correct.
        buildRenderers();
    }

    @Override
    public void onTimestampResolved(UtcTimingElement utcTiming, long elapsedRealtimeOffset) {
        this.elapsedRealtimeOffset = elapsedRealtimeOffset;
        buildRenderers();
    }

    public void destroy() {
        requireManifest = false;
    }
}