package ai.nuxie.sdk.runtime;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaTimestamp;
import android.media.PlaybackParams;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Decoder/audio owner only: no View, no overlay, and no renderer GL context.
 * A private OES surface converts platform-decoded frames to bounded RGBA for
 * the runtime's Vulkan factory. This initial path includes a GPU readback.
 * All media/GL mutations use one worker. Sources are retained, verified local
 * files supplied by SDK acquisition; this owner never downloads media.
 */
final class AndroidVideoDecoder {
  public static final class Frame {
    public final long generation;
    public final double seconds;
    public final int width, height;
    public final byte[] rgba;
    Frame(long generation, double seconds, int width, int height, byte[] rgba) {
      this.generation = generation;
      this.seconds = seconds;
      this.width = width;
      this.height = height;
      this.rgba = rgba;
    }
  }
  public static final class Clock {
    public final long generation;
    public final double seconds, rate;
    public final boolean playing;
    Clock(long generation, double seconds, double rate, boolean playing) {
      this.generation = generation;
      this.seconds = seconds;
      this.rate = rate;
      this.playing = playing;
    }
  }
  private static final class ClockSnapshot {
    final long epoch, generation, captured, anchor;
    final double seconds, rate;
    ClockSnapshot(long epoch, long generation, long captured, long anchor,
                  double seconds, double rate) {
      this.epoch = epoch;
      this.generation = generation;
      this.captured = captured;
      this.anchor = anchor;
      this.seconds = seconds;
      this.rate = rate;
    }
  }
  private final AtomicLong clockEpoch = new AtomicLong();
  private final AtomicBoolean clockRefreshPending = new AtomicBoolean();
  private volatile ClockSnapshot clockSnapshot;
  private void invalidateClock() {
    clockEpoch.incrementAndGet();
    clockSnapshot = null;
  }
  @SuppressWarnings("deprecation")
  private void refreshClock(long epoch) {
    try {
      if (closed || !ready || seeking || failure != null ||
          clockEpoch.get() != epoch)
        return;
      MediaTimestamp timestamp = player.getTimestamp();
      if (timestamp == null || timestamp.getAnchorMediaTimeUs() < 0)
        return;
      long anchor = android.os.Build.VERSION.SDK_INT >= 29
                        ? timestamp.getAnchorSystemNanoTime()
                        : timestamp.getAnchorSytemNanoTime();
      double rate = timestamp.getMediaClockRate();
      if ((Double.isNaN(rate) || Double.isInfinite(rate)) || rate < 0)
        return;
      clockSnapshot = new ClockSnapshot(
          epoch, generation, System.nanoTime(), anchor,
          timestamp.getAnchorMediaTimeUs() / 1_000_000.0, rate);
    } catch (Exception e) {
      fail("media clock: " + e);
    } finally {
      clockRefreshPending.set(false);
    }
  }
  // All MediaPlayer calls stay on its owner thread. At most one refresh is
  // queued; stale observations are unavailable rather than extrapolated
  // forever.
  public Clock clock() {
    if (closed || failure != null)
      return null;
    long epoch = clockEpoch.get();
    if (clockRefreshPending.compareAndSet(false, true) &&
        !handler.post(() -> refreshClock(epoch)))
      clockRefreshPending.set(false);
    ClockSnapshot sample = clockSnapshot;
    long now = System.nanoTime();
    if (sample == null || sample.epoch != epoch || now - sample.captured < 0 ||
        now - sample.captured > 100_000_000L)
      return null;
    double seconds =
        sample.seconds + (now - sample.anchor) / 1_000_000_000.0 * sample.rate;
    if ((Double.isNaN(seconds) || Double.isInfinite(seconds)) || seconds < 0)
      return null;
    return new Clock(sample.generation, Math.min(seconds, duration),
                     sample.rate, playing && !ended && sample.rate > 0);
  }
  private final HandlerThread thread = new HandlerThread("NuxieVideo");
  private final Handler handler;
  private final int maxFrameBytes;
  private volatile boolean ready, playing, ended, closed;
  private volatile String failure;
  private volatile double duration;
  private Frame latest;
  private MediaPlayer player;
  private SurfaceTexture texture;
  private Surface surface;
  private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
  private EGLContext context = EGL14.EGL_NO_CONTEXT;
  private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
  private EGLConfig config;
  private int width, height, textureId, program;
  private ByteBuffer readback;
  private long generation;
  private boolean seeking, wantsPlay;
  private double queuedSeek = -1;
  private long queuedGeneration;
  private float rate = 1, volume = 0;
  private final AudioManager audio;
  private final int audioPolicy;
  private boolean ownsFocus;
  private volatile boolean interrupted;
  private final AtomicBoolean interruptionEnded = new AtomicBoolean();
  private final AtomicBoolean permanentLoss = new AtomicBoolean();
  private final AtomicBoolean playBlocked = new AtomicBoolean();
  private final AudioManager.OnAudioFocusChangeListener focusListener =
      this::focusChanged;
  private void focusChanged(int change) {
    invalidateClock();
    handler.post(() -> {
      if (closed)
        return;
      if (change == AudioManager.AUDIOFOCUS_GAIN) {
        if (interrupted) interruptionEnded.set(true);
        interrupted = false;
        player.setVolume(volume, volume);
      } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
        player.setVolume(volume * 0.2f, volume * 0.2f);
      } else {
        if (ready)
          player.pause();
        playing = false;
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
          permanentLoss.set(true);
          interrupted = false;
          ownsFocus = false;
          wantsPlay = false;
        } else
          interrupted = true;
      }
    });
  }
  private final float[] textureTransform = new float[16];
  private final FloatBuffer vertices = ByteBuffer.allocateDirect(16 * 4)
                                           .order(ByteOrder.nativeOrder())
                                           .asFloatBuffer();

  AndroidVideoDecoder(Context application, java.io.File source, long generation,
                     int maxFrameBytes, int audioPolicy) {
    if (!source.isFile() || maxFrameBytes <= 0 || maxFrameBytes > 67_108_864)
      throw new IllegalArgumentException("invalid local video source or frame budget");
    this.audio =
        (AudioManager)application.getApplicationContext().getSystemService(
            Context.AUDIO_SERVICE);
    this.audioPolicy = audioPolicy;
    this.generation = generation;
    this.maxFrameBytes = maxFrameBytes;
    vertices
        .put(new float[] {-1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1, 1, 1, 1, 1})
        .position(0);
    thread.start();
    handler = new Handler(thread.getLooper());
    handler.post(() -> open(source));
  }
  private void open(java.io.File source) {
    try {
      initGl();
      player = new MediaPlayer();
      player.setVolume(0, 0);
      player.setSurface(surface);
      player.setOnVideoSizeChangedListener((p, w, h) -> {
        try {
          resize(w, h);
        } catch (Exception e) {
          fail(e.toString());
        }
      });
      player.setOnPreparedListener(p -> {
        duration = p.getDuration() / 1000.0;
        ready = true;
        if (queuedSeek >= 0) {
          double target = queuedSeek;
          long token = queuedGeneration;
          queuedSeek = -1;
          beginSeek(target, token);
        }
      });
      player.setOnCompletionListener(p -> {
        invalidateClock();
        playing = false;
        ended = true;
      });
      player.setOnErrorListener((p, what, extra) -> {
        fail("MediaPlayer " + what + ":" + extra);
        return true;
      });
      player.setOnSeekCompleteListener(p -> {
        if (queuedSeek >= 0) {
          double target = queuedSeek;
          long token = queuedGeneration;
          queuedSeek = -1;
          beginSeek(target, token);
        } else {
          seeking = false;
          if (wantsPlay && !interrupted && acquireFocus()) {
            try {
              // Rate commands received while preparing/seeking are deferred.
              // Apply the retained rate before any resumed media is played.
              p.setPlaybackParams(new PlaybackParams().setSpeed(rate));
              p.start();
              playing = true;
            } catch (Exception e) {
              fail("resume after seek: " + e);
            }
          }
        }
      });
      try (java.io.FileInputStream input = new java.io.FileInputStream(source)) {
        player.setDataSource(input.getFD());
      }
      player.prepareAsync();
    } catch (Exception e) {
      fail(e.toString());
    }
  }
  private void initGl() {
    display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
    int[] versions = new int[2];
    if (!EGL14.eglInitialize(display, versions, 0, versions, 1))
      throw new IllegalStateException("EGL initialize");
    EGLConfig[] configs = new EGLConfig[1];
    int[] count = new int[1];
    int[] attrs = {EGL14.EGL_RENDERABLE_TYPE,
                   EGL14.EGL_OPENGL_ES2_BIT,
                   EGL14.EGL_SURFACE_TYPE,
                   EGL14.EGL_PBUFFER_BIT,
                   EGL14.EGL_RED_SIZE,
                   8,
                   EGL14.EGL_GREEN_SIZE,
                   8,
                   EGL14.EGL_BLUE_SIZE,
                   8,
                   EGL14.EGL_ALPHA_SIZE,
                   8,
                   EGL14.EGL_NONE};
    if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) ||
        count[0] == 0)
      throw new IllegalStateException("EGL config");
    config = configs[0];
    context = EGL14.eglCreateContext(
        display, config, EGL14.EGL_NO_CONTEXT,
        new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
    resize(1, 1);
    int[] ids = new int[1];
    GLES20.glGenTextures(1, ids, 0);
    textureId = ids[0];
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                           GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                           GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                           GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                           GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    texture = new SurfaceTexture(textureId);
    texture.setOnFrameAvailableListener(t -> onFrame(), handler);
    surface = new Surface(texture);
    int vertex = shader(GLES20.GL_VERTEX_SHADER,
                        "attribute vec2 position;attribute vec2 uv;uniform " +
                        "mat4 transform;varying vec2 tex;void "
                            + "main(){gl_Position=vec4(position,0.,1.);tex=(" +
                              "transform*vec4(uv,0.,1.)).xy;}");
    int fragment =
        shader(GLES20.GL_FRAGMENT_SHADER,
               "#extension GL_OES_EGL_image_external : require\nprecision " +
               "mediump float;uniform "
                   + "samplerExternalOES frame;varying vec2 tex;void "
                   + "main(){gl_FragColor=texture2D(frame,tex);}");
    program = GLES20.glCreateProgram();
    GLES20.glAttachShader(program, vertex);
    GLES20.glAttachShader(program, fragment);
    GLES20.glLinkProgram(program);
    int[] status = new int[1];
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
    GLES20.glDeleteShader(vertex);
    GLES20.glDeleteShader(fragment);
    if (status[0] == 0)
      throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
  }
  private int shader(int kind, String source) {
    int id = GLES20.glCreateShader(kind);
    GLES20.glShaderSource(id, source);
    GLES20.glCompileShader(id);
    int[] status = new int[1];
    GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0);
    if (status[0] == 0) {
      String log = GLES20.glGetShaderInfoLog(id);
      GLES20.glDeleteShader(id);
      throw new IllegalStateException(log);
    }
    return id;
  }
  private void resize(int w, int h) {
    if (w <= 0 || h <= 0 || (long)w * h * 4 > maxFrameBytes)
      throw new IllegalArgumentException("video frame exceeds budget");
    if (width == w && height == h)
      return;
    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                         EGL14.EGL_NO_CONTEXT);
    if (eglSurface != EGL14.EGL_NO_SURFACE)
      EGL14.eglDestroySurface(display, eglSurface);
    eglSurface = EGL14.eglCreatePbufferSurface(
        display, config,
        new int[] {EGL14.EGL_WIDTH, w, EGL14.EGL_HEIGHT, h, EGL14.EGL_NONE}, 0);
    if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context))
      throw new IllegalStateException("EGL make current");
    width = w;
    height = h;
    readback = ByteBuffer.allocateDirect(w * h * 4);
    if (texture != null)
      texture.setDefaultBufferSize(w, h);
  }
  private void onFrame() {
    if (closed || failure != null)
      return;
    try {
      texture.updateTexImage();
      if (seeking || !ready)
        return;
      observeDecoderInfo();
      texture.getTransformMatrix(textureTransform);
      GLES20.glViewport(0, 0, width, height);
      GLES20.glUseProgram(program);
      GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
      GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
      GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "frame"), 0);
      GLES20.glUniformMatrix4fv(
          GLES20.glGetUniformLocation(program, "transform"), 1, false,
          textureTransform, 0);
      int position = GLES20.glGetAttribLocation(program, "position"),
          uv = GLES20.glGetAttribLocation(program, "uv");
      vertices.position(0);
      GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16,
                                   vertices);
      GLES20.glEnableVertexAttribArray(position);
      vertices.position(2);
      GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices);
      GLES20.glEnableVertexAttribArray(uv);
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
      ByteBuffer pixels = readback;
      pixels.clear();
      GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA,
                          GLES20.GL_UNSIGNED_BYTE, pixels);
      if (GLES20.glGetError() != GLES20.GL_NO_ERROR)
        throw new IllegalStateException("video readback failed");
      byte[] rgba = new byte[width * height * 4];
      for (int y = 0; y < height; y++) {
        pixels.position((height - 1 - y) * width * 4);
        pixels.get(rgba, y * width * 4, width * 4);
      }
      Frame frame = new Frame(generation, player.getCurrentPosition() / 1000.0,
                              width, height, rgba);
      synchronized (this) { latest = frame; }
    } catch (Exception e) {
      fail(e.toString());
    }
  }
  public void action(int action, double value, long token) {
    invalidateClock();
    if (closed)
      return;
    handler.post(() -> {
      if (closed || failure != null)
        return;
      try {
        switch (action) {
        case 0:
          wantsPlay = true;
          if (ready && !seeking && !interrupted && acquireFocus()) {
            player.setPlaybackParams(new PlaybackParams().setSpeed(rate));
            player.start();
            playing = true;
          }
          break;
        case 1:
          wantsPlay = false;
          if (ready)
            player.pause();
          playing = false;
          // Retain the focus request during transient loss so Android can
          // deliver AUDIOFOCUS_GAIN. Runtime intent decides whether to resume.
          if (!interrupted) releaseFocus();
          break;
        case 2:
          synchronized (this) { latest = null; }
          ended = false;
          if (seeking || !ready) {
            queuedSeek = value;
            queuedGeneration = token;
            if (!ready)
              generation = token;
          } else
            beginSeek(value, token);
          break;
        case 3:
          rate = (float)value;
          if (ready && playing)
            player.setPlaybackParams(new PlaybackParams().setSpeed(rate));
          break;
        case 4:
          volume = (float)value;
          if (volume == 0)
            releaseFocus();
          if (!wantsPlay || acquireFocus())
            player.setVolume(volume, volume);
          else {
            if (ready)
              player.pause();
            playing = false;
          }
          break;
        default:
          throw new IllegalArgumentException("unknown video command");
        }
      } catch (Exception e) {
        fail(e.toString());
      }
    });
  }
  @SuppressWarnings("deprecation")
  private boolean acquireFocus() {
    if (volume == 0 || audioPolicy == 1 || ownsFocus)
      return true;
    int kind = audioPolicy == 2
                   ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                   : AudioManager.AUDIOFOCUS_GAIN_TRANSIENT;
    ownsFocus = audio.requestAudioFocus(focusListener,
                                        AudioManager.STREAM_MUSIC, kind) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    if (!ownsFocus)
      playBlocked.set(true);
    return ownsFocus;
  }
  @SuppressWarnings("deprecation")
  private void releaseFocus() {
    if (ownsFocus) {
      audio.abandonAudioFocus(focusListener);
      ownsFocus = false;
    }
  }
  public boolean interrupted() { return interrupted; }
  public boolean takeInterruptionEnded() { return interruptionEnded.getAndSet(false); }
  public boolean takePermanentLoss() { return permanentLoss.getAndSet(false); }
  public boolean takePlayBlocked() { return playBlocked.getAndSet(false); }
  private void beginSeek(double seconds, long token) {
    if ((Double.isNaN(seconds) || Double.isInfinite(seconds)) || seconds < 0)
      throw new IllegalArgumentException("invalid seek");
    seeking = true;
    generation = token;
    if (android.os.Build.VERSION.SDK_INT >= 26)
      player.seekTo((long)(seconds * 1000), MediaPlayer.SEEK_CLOSEST);
    else
      player.seekTo((int)Math.min(Integer.MAX_VALUE, seconds * 1000));
  }
  private void fail(String message) {
    invalidateClock();
    failure = message;
    playing = false;
    if (player != null) {
      try {
        player.pause();
      } catch (Exception ignored) {
      }
    }
  }
  public boolean ready() { return ready; }
  public boolean playing() { return playing; }
  public boolean ended() { return ended; }
  public String failure() { return failure; }
  public double duration() { return duration; }
  public synchronized Frame takeFrame() {
    Frame frame = latest;
    latest = null;
    return frame;
  }
  public static final class DecoderInfo {
    public final String name;
    // 0 unknown, 1 reported hardware, 2 reported software.
    public final int acceleration;
    // Advisory API value, not an admission limit or observed concurrent capacity.
    public final int advertisedMaxInstances;
    DecoderInfo(String name, int acceleration, int advertisedMaxInstances) {
      this.name = name;
      this.acceleration = acceleration;
      this.advertisedMaxInstances = advertisedMaxInstances;
    }
  }
  private volatile DecoderInfo decoderInfo;
  private long nextDecoderInfoProbe;
  public DecoderInfo decoderInfo() { return closed ? null : decoderInfo; }
  private void observeDecoderInfo() {
    if (android.os.Build.VERSION.SDK_INT < 26 || decoderInfo != null || player == null) return;
    long now = android.os.SystemClock.elapsedRealtime();
    if (now < nextDecoderInfoProbe) return;
    nextDecoderInfoProbe = now + 1000;
    try {
      android.os.PersistableBundle metrics = player.getMetrics();
      if (metrics == null) return;
      String name = metrics.getString(MediaPlayer.MetricsConstants.CODEC_VIDEO);
      String mime = metrics.getString(MediaPlayer.MetricsConstants.MIME_TYPE_VIDEO);
      if (name == null || name.isEmpty()) return;
      int acceleration = 0, instances = 0;
      for (android.media.MediaCodecInfo info :
           new android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).getCodecInfos()) {
        if (info.isEncoder() || !info.getName().equals(name)) continue;
        if (android.os.Build.VERSION.SDK_INT >= 29) {
          if (info.isSoftwareOnly()) acceleration = 2;
          else if (info.isHardwareAccelerated()) acceleration = 1;
        }
        if (mime != null) {
          try { instances = Math.max(0, info.getCapabilitiesForType(mime).getMaxSupportedInstances()); }
          catch (IllegalArgumentException ignored) { /* Codec identity remains useful. */ }
        }
        break;
      }
      decoderInfo = new DecoderInfo(name, acceleration, instances);
    } catch (RuntimeException ignored) {
      // Optional diagnostics must not fail otherwise valid playback. Retry boundedly.
    }
  }
  private volatile RuntimeException closeFailure;
  public void close() {
    if (Thread.currentThread() == thread)
      throw new IllegalStateException("video decoder cannot synchronously close its own worker");
    synchronized (this) {
      if (!closed) {
        closed = true;
        invalidateClock();
        latest = null;
        if (!handler.post(() -> {
          try {
            releaseFocus();
            if (player != null) {
              player.release();
              player = null;
            }
            if (surface != null)
              surface.release();
            if (texture != null)
              texture.release();
            if (display != EGL14.EGL_NO_DISPLAY) {
              EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                                   EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
              if (eglSurface != EGL14.EGL_NO_SURFACE)
                EGL14.eglDestroySurface(display, eglSurface);
              if (context != EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroyContext(display, context);
              // The default display may also belong to the app's own GL
              // contexts. Destroy only our context/surface, never its display.
              EGL14.eglReleaseThread();
            }
          } catch (RuntimeException error) {
            closeFailure = error;
          } finally {
            thread.quitSafely();
          }
        })) closeFailure = new IllegalStateException("video teardown worker unavailable");
      }
    }
    // Scene admission may reuse this decoder slot immediately after close.
    // Wait for the private worker to release MediaPlayer/SurfaceTexture first.
    if (Thread.currentThread() != thread) {
      try {
        thread.join(2000);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "interrupted while closing video decoder", error);
      }
      if (thread.isAlive())
        throw new IllegalStateException("video decoder teardown timed out");
    }
    if (closeFailure != null)
      throw new IllegalStateException("video decoder teardown failed", closeFailure);
  }
}
