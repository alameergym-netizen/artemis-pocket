package com.limelight.pocket.video;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import com.limelight.LimeLog;
import com.limelight.utils.Stereo3DRenderer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Optional client-side frame generation ("Frame generation 2x").
 *
 * The decoder renders into a SurfaceTexture instead of the screen. Every new frame is copied to
 * a texture and a small luma pyramid. Motion between the last two frames is found by block
 * matching (coarse 1/16 search, fine 1/4 refinement with sub-pixel fit, 3x3 median), then a
 * motion-compensated in-between frame is drawn half a refresh before the real one. Where motion
 * can't be trusted (things appearing, poor matches) it falls back to the newer frame instead of
 * smearing. On a 120 Hz screen a 60 FPS stream is shown at 120 FPS, for half a frame of extra
 * delay. If the stream already runs near the screen rate, frames are shown as they are.
 *
 * Any GPU problem turns generation off and keeps showing the real frames.
 */
public class FrameGenRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    /** For the performance overlay / stream menu. */
    public static volatile float outputFps = 0;
    public static volatile boolean generating = false;

    private final GLSurfaceView view;
    private final Stereo3DRenderer.OnSurfaceReadyListener listener;
    private final int width;
    private final int height;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger pendingFrames = new AtomicInteger();
    private final float[] texMatrix = new float[16];
    private final FloatBuffer quad;

    private SurfaceTexture surfaceTexture;
    private Surface videoSurface;
    private boolean surfaceAnnounced;
    private int oesTexture;

    private int copyProgram, downProgram, coarseProgram, fineProgram, medianProgram, interpProgram, blitProgram;
    private final int[] frameTex = new int[2], frameFbo = new int[2];
    private final int[] l1Tex = new int[2], l1Fbo = new int[2];
    private final int[] l2Tex = new int[2], l2Fbo = new int[2];
    private int coarseTex, coarseFbo, fineTex, fineFbo, mvTex, mvFbo;
    private int l1w, l1h, l2w, l2h, cw, ch, fw, fh;

    private boolean ready;          // textures and programs exist
    private boolean interpOk;       // motion passes work on this GPU
    private int cur = 0;
    private int framesSeen = 0;
    private boolean pendingReal;
    private int viewW = 1, viewH = 1;

    private long lastFrameNs, lastDrawNs, fpsWindowStart;
    private float frameIntervalMs = 16.7f, drawIntervalMs = 8.3f;
    private int fpsCount;

    public FrameGenRenderer(GLSurfaceView view, Stereo3DRenderer.OnSurfaceReadyListener listener, int width, int height) {
        this.view = view;
        this.listener = listener;
        this.width = Math.max(64, width);
        this.height = Math.max(64, height);
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
    }

    // ---- Lifecycle ----------------------------------------------------------------------

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        ready = false;
        interpOk = false;
        framesSeen = 0;
        try {
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            oesTexture = t[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            if (surfaceTexture != null) {
                surfaceTexture.release();
            }
            surfaceTexture = new SurfaceTexture(oesTexture);
            surfaceTexture.setDefaultBufferSize(width, height);
            surfaceTexture.setOnFrameAvailableListener(this);
            final Surface previous = videoSurface;
            videoSurface = new Surface(surfaceTexture);
            if (previous != null) {
                previous.release();
            }

            copyProgram = program(VS_OES, FS_OES);
            blitProgram = program(VS, FS_BLIT);
            for (int i = 0; i < 2; i++) {
                frameTex[i] = texture(width, height, GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, GLES30.GL_LINEAR);
                frameFbo[i] = framebuffer(frameTex[i]);
            }
            ready = copyProgram != 0 && blitProgram != 0 && frameFbo[0] != 0 && frameFbo[1] != 0;
            setupMotion();
        } catch (RuntimeException e) {
            LimeLog.warning("Frame generation setup failed: " + e);
            interpOk = false;
        }
        LimeLog.info("Frame generation: " + (interpOk ? "ready" : (ready ? "copy only" : "unavailable"))
                + " (" + width + "x" + height + ")");

        if (!surfaceAnnounced) {
            surfaceAnnounced = true;
            final Surface surface = videoSurface;
            main.post(() -> listener.onStereo3DSurfaceReady(surface));
        }
    }

    private void setupMotion() {
        String version = GLES20.glGetString(GLES20.GL_VERSION);
        String ext = GLES20.glGetString(GLES20.GL_EXTENSIONS);
        boolean floatTargets = (version != null && (version.contains("OpenGL ES 3.2") || version.contains("OpenGL ES 4")))
                || (ext != null && (ext.contains("GL_EXT_color_buffer_half_float") || ext.contains("GL_EXT_color_buffer_float")));
        if (!floatTargets) {
            LimeLog.warning("Frame generation: no float render targets");
            return;
        }
        downProgram = program(VS, FS_DOWN);
        coarseProgram = program(VS, FS_ME_COARSE);
        fineProgram = program(VS, FS_ME_FINE);
        medianProgram = program(VS, FS_MEDIAN);
        interpProgram = program(VS, FS_INTERP);
        if (downProgram == 0 || coarseProgram == 0 || fineProgram == 0 || medianProgram == 0 || interpProgram == 0) {
            return;
        }
        l1w = Math.max(1, width / 4);
        l1h = Math.max(1, height / 4);
        l2w = Math.max(1, width / 16);
        l2h = Math.max(1, height / 16);
        cw = (width + 63) / 64;
        ch = (height + 63) / 64;
        fw = (width + 31) / 32;
        fh = (height + 31) / 32;
        for (int i = 0; i < 2; i++) {
            l1Tex[i] = texture(l1w, l1h, GLES30.GL_R8, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, GLES30.GL_LINEAR);
            l1Fbo[i] = framebuffer(l1Tex[i]);
            l2Tex[i] = texture(l2w, l2h, GLES30.GL_R8, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, GLES30.GL_LINEAR);
            l2Fbo[i] = framebuffer(l2Tex[i]);
        }
        coarseTex = texture(cw, ch, GLES30.GL_RGBA16F, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, GLES30.GL_NEAREST);
        coarseFbo = framebuffer(coarseTex);
        fineTex = texture(fw, fh, GLES30.GL_RGBA16F, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, GLES30.GL_NEAREST);
        fineFbo = framebuffer(fineTex);
        mvTex = texture(fw, fh, GLES30.GL_RGBA16F, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, GLES30.GL_LINEAR);
        mvFbo = framebuffer(mvTex);
        interpOk = l1Fbo[0] != 0 && l1Fbo[1] != 0 && l2Fbo[0] != 0 && l2Fbo[1] != 0
                && coarseFbo != 0 && fineFbo != 0 && mvFbo != 0;
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        viewW = Math.max(1, w);
        viewH = Math.max(1, h);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        pendingFrames.incrementAndGet();
    }

    /** The stream is going away. */
    public void release() {
        generating = false;
        outputFps = 0;
        view.queueEvent(() -> {
            if (surfaceTexture != null) {
                surfaceTexture.release();
                surfaceTexture = null;
            }
            ready = false;
            interpOk = false;
        });
        Surface s = videoSurface;
        videoSurface = null;
        if (s != null) {
            s.release();
        }
    }

    // ---- Per refresh --------------------------------------------------------------------

    @Override
    public void onDrawFrame(GL10 unused) {
        long now = System.nanoTime();
        if (lastDrawNs != 0) {
            float dt = (now - lastDrawNs) / 1e6f;
            if (dt > 0 && dt < 100) {
                drawIntervalMs = drawIntervalMs * 0.95f + dt * 0.05f;
            }
        }
        lastDrawNs = now;

        if (!ready || surfaceTexture == null) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glClearColor(0, 0, 0, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            return;
        }

        boolean newFrame = false;
        int n = pendingFrames.getAndSet(0);
        try {
            for (int i = 0; i < n; i++) {
                surfaceTexture.updateTexImage();
                newFrame = true;
            }
        } catch (RuntimeException e) {
            newFrame = false;
        }

        try {
            if (newFrame) {
                if (lastFrameNs != 0) {
                    float dt = (now - lastFrameNs) / 1e6f;
                    if (dt > 0 && dt < 200) {
                        frameIntervalMs = frameIntervalMs * 0.9f + dt * 0.1f;
                    }
                }
                lastFrameNs = now;
                surfaceTexture.getTransformMatrix(texMatrix);
                cur ^= 1;
                copyFrame(cur);
                framesSeen++;
                // Only worth it when the screen refreshes at least ~1.6x faster than frames arrive
                boolean generate = interpOk && framesSeen >= 2 && frameIntervalMs > 1.6f * drawIntervalMs;
                if (interpOk) {
                    buildPyramid(cur);
                }
                if (generate) {
                    estimateMotion();
                    drawInterpolated(0.5f);
                    pendingReal = true;
                }
                else {
                    blit(frameTex[cur]);
                    pendingReal = false;
                }
                generating = generate;
            }
            else if (framesSeen > 0) {
                // The real frame after a generated one, or a repeat while waiting
                blit(frameTex[cur]);
                pendingReal = false;
            }
            else {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                GLES20.glClearColor(0, 0, 0, 1);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            }
        } catch (RuntimeException e) {
            LimeLog.warning("Frame generation turned off: " + e);
            interpOk = false;
            generating = false;
        }

        fpsCount++;
        if (fpsWindowStart == 0) {
            fpsWindowStart = now;
        }
        else if (now - fpsWindowStart >= 1_000_000_000L) {
            outputFps = fpsCount * 1e9f / (now - fpsWindowStart);
            fpsCount = 0;
            fpsWindowStart = now;
        }
    }

    // ---- Passes -------------------------------------------------------------------------

    private void copyFrame(int index) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameFbo[index]);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glUseProgram(copyProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(copyProgram, "uTex"), 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(copyProgram, "uTexMatrix"), 1, false, texMatrix, 0);
        drawQuad();
    }

    private void buildPyramid(int index) {
        GLES20.glUseProgram(downProgram);
        int src = GLES20.glGetUniformLocation(downProgram, "uSrc");
        int texel = GLES20.glGetUniformLocation(downProgram, "uSrcTexel");
        int fromLuma = GLES20.glGetUniformLocation(downProgram, "uFromLuma");

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, l1Fbo[index]);
        GLES20.glViewport(0, 0, l1w, l1h);
        bind(0, frameTex[index], src);
        GLES20.glUniform2f(texel, 1f / width, 1f / height);
        GLES20.glUniform1f(fromLuma, 0f);
        drawQuad();

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, l2Fbo[index]);
        GLES20.glViewport(0, 0, l2w, l2h);
        bind(0, l1Tex[index], src);
        GLES20.glUniform2f(texel, 1f / l1w, 1f / l1h);
        GLES20.glUniform1f(fromLuma, 1f);
        drawQuad();
    }

    private void estimateMotion() {
        int prev = cur ^ 1;
        GLES20.glUseProgram(coarseProgram);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, coarseFbo);
        GLES20.glViewport(0, 0, cw, ch);
        bind(0, l2Tex[prev], GLES20.glGetUniformLocation(coarseProgram, "uPrev"));
        bind(1, l2Tex[cur], GLES20.glGetUniformLocation(coarseProgram, "uCur"));
        drawQuad();

        GLES20.glUseProgram(fineProgram);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fineFbo);
        GLES20.glViewport(0, 0, fw, fh);
        bind(0, l1Tex[prev], GLES20.glGetUniformLocation(fineProgram, "uPrev"));
        bind(1, l1Tex[cur], GLES20.glGetUniformLocation(fineProgram, "uCur"));
        bind(2, coarseTex, GLES20.glGetUniformLocation(fineProgram, "uCoarse"));
        drawQuad();

        GLES20.glUseProgram(medianProgram);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mvFbo);
        GLES20.glViewport(0, 0, fw, fh);
        bind(0, fineTex, GLES20.glGetUniformLocation(medianProgram, "uMv"));
        drawQuad();
    }

    private void drawInterpolated(float alpha) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, viewW, viewH);
        GLES20.glUseProgram(interpProgram);
        bind(0, frameTex[cur ^ 1], GLES20.glGetUniformLocation(interpProgram, "uPrevFrame"));
        bind(1, frameTex[cur], GLES20.glGetUniformLocation(interpProgram, "uCurFrame"));
        bind(2, mvTex, GLES20.glGetUniformLocation(interpProgram, "uMv"));
        GLES20.glUniform1f(GLES20.glGetUniformLocation(interpProgram, "uAlpha"), alpha);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(interpProgram, "uTexel"), 1f / width, 1f / height);
        drawQuad();
    }

    private void blit(int texture) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, viewW, viewH);
        GLES20.glUseProgram(blitProgram);
        bind(0, texture, GLES20.glGetUniformLocation(blitProgram, "uTex"));
        drawQuad();
    }

    // ---- GL helpers ---------------------------------------------------------------------

    private void bind(int unit, int texture, int uniform) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(uniform, unit);
    }

    private void drawQuad() {
        quad.position(0);
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private static int texture(int w, int h, int internalFormat, int format, int type, int filter) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, internalFormat, w, h, 0, format, type, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    /** A framebuffer drawing into the texture, or 0 if this GPU can't. */
    private static int framebuffer(int texture) {
        int[] f = new int[1];
        GLES20.glGenFramebuffers(1, f, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            LimeLog.warning("Frame generation: framebuffer incomplete " + status);
            GLES20.glDeleteFramebuffers(1, f, 0);
            return 0;
        }
        return f[0];
    }

    private static int shader(int type, String source) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, source);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            LimeLog.warning("Frame generation shader: " + GLES20.glGetShaderInfoLog(s));
            GLES20.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    private static int program(String vs, String fs) {
        int v = shader(GLES20.GL_VERTEX_SHADER, vs);
        int f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) {
            return 0;
        }
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glBindAttribLocation(p, 0, "aPos");
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        if (ok[0] == 0) {
            LimeLog.warning("Frame generation program: " + GLES20.glGetProgramInfoLog(p));
            GLES20.glDeleteProgram(p);
            return 0;
        }
        return p;
    }

    // ---- Shaders (tested against synthetic motion in WebGL2, same GLSL ES 3.00) -----------

    private static final String VS =
            "#version 300 es\n" +
            "in vec2 aPos;\n" +
            "out vec2 vUv;\n" +
            "void main() {\n" +
            "    vUv = aPos * 0.5 + 0.5;\n" +
            "    gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "}\n";

    private static final String FS_DOWN =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "uniform sampler2D uSrc;\n" +
            "uniform vec2 uSrcTexel;\n" +
            "uniform float uFromLuma;\n" +
            "in vec2 vUv;\n" +
            "out vec4 outColor;\n" +
            "float lum(vec2 uv) {\n" +
            "    vec4 c = texture(uSrc, uv);\n" +
            "    return mix(dot(c.rgb, vec3(0.299, 0.587, 0.114)), c.r, uFromLuma);\n" +
            "}\n" +
            "void main() {\n" +
            "    vec2 d = uSrcTexel;\n" +
            "    float l = lum(vUv + vec2(-d.x, -d.y)) + lum(vUv + vec2(d.x, -d.y))\n" +
            "            + lum(vUv + vec2(-d.x, d.y)) + lum(vUv + vec2(d.x, d.y));\n" +
            "    outColor = vec4(l * 0.25, 0.0, 0.0, 1.0);\n" +
            "}\n";

    private static final String FS_ME_COARSE =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            "uniform highp sampler2D uPrev;\n" +
            "uniform highp sampler2D uCur;\n" +
            "out vec4 outColor;\n" +
            "const int B = 4;\n" +
            "const int R = 6;\n" +
            "ivec2 gSize;\n" +
            "float lumPrev(ivec2 p) { return texelFetch(uPrev, clamp(p, ivec2(0), gSize - 1), 0).r; }\n" +
            "float lumCur(ivec2 p) { return texelFetch(uCur, clamp(p, ivec2(0), gSize - 1), 0).r; }\n" +
            "void main() {\n" +
            "    gSize = textureSize(uCur, 0);\n" +
            "    ivec2 o = ivec2(gl_FragCoord.xy) * B;\n" +
            "    float cur[16];\n" +
            "    for (int j = 0; j < B; j++) {\n" +
            "        for (int i = 0; i < B; i++) {\n" +
            "            cur[j * B + i] = lumCur(o + ivec2(i, j));\n" +
            "        }\n" +
            "    }\n" +
            "    float best = 1e9;\n" +
            "    ivec2 bv = ivec2(0);\n" +
            "    for (int dy = -R; dy <= R; dy++) {\n" +
            "        for (int dx = -R; dx <= R; dx++) {\n" +
            "            float s = 0.0;\n" +
            "            for (int j = 0; j < B; j++) {\n" +
            "                for (int i = 0; i < B; i++) {\n" +
            "                    s += abs(cur[j * B + i] - lumPrev(o + ivec2(i + dx, j + dy)));\n" +
            "                }\n" +
            "            }\n" +
            "            s = s / float(B * B) + 0.0015 * float(abs(dx) + abs(dy));\n" +
            "            if (s < best) {\n" +
            "                best = s;\n" +
            "                bv = ivec2(dx, dy);\n" +
            "            }\n" +
            "        }\n" +
            "    }\n" +
            "    outColor = vec4(vec2(bv), best, 1.0);\n" +
            "}\n";

    private static final String FS_ME_FINE =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            "uniform highp sampler2D uPrev;\n" +
            "uniform highp sampler2D uCur;\n" +
            "uniform highp sampler2D uCoarse;\n" +
            "out vec4 outColor;\n" +
            "const int B = 8;\n" +
            "const int R = 2;\n" +
            "const int N = 5;\n" +
            "ivec2 gSize;\n" +
            "float lumPrev(ivec2 p) { return texelFetch(uPrev, clamp(p, ivec2(0), gSize - 1), 0).r; }\n" +
            "float lumCur(ivec2 p) { return texelFetch(uCur, clamp(p, ivec2(0), gSize - 1), 0).r; }\n" +
            "float cur[64];\n" +
            "float sadAt(ivec2 o, ivec2 v) {\n" +
            "    float s = 0.0;\n" +
            "    for (int j = 0; j < B; j++) {\n" +
            "        for (int i = 0; i < B; i++) {\n" +
            "            s += abs(cur[j * B + i] - lumPrev(o + ivec2(i, j) + v));\n" +
            "        }\n" +
            "    }\n" +
            "    return s / float(B * B);\n" +
            "}\n" +
            "void main() {\n" +
            "    gSize = textureSize(uCur, 0);\n" +
            "    ivec2 blk = ivec2(gl_FragCoord.xy);\n" +
            "    ivec2 o = blk * B;\n" +
            "    for (int j = 0; j < B; j++) {\n" +
            "        for (int i = 0; i < B; i++) {\n" +
            "            cur[j * B + i] = lumCur(o + ivec2(i, j));\n" +
            "        }\n" +
            "    }\n" +
            "    ivec2 cSize = textureSize(uCoarse, 0);\n" +
            "    vec4 c = texelFetch(uCoarse, clamp(blk / 2, ivec2(0), cSize - 1), 0);\n" +
            "    ivec2 pred = ivec2(round(c.xy * 4.0));\n" +
            "\n" +
            "    float sads[25];\n" +
            "    float best = 1e9;\n" +
            "    int bi = 12;\n" +
            "    for (int dy = -R; dy <= R; dy++) {\n" +
            "        for (int dx = -R; dx <= R; dx++) {\n" +
            "            ivec2 v = pred + ivec2(dx, dy);\n" +
            "            float raw = sadAt(o, v);\n" +
            "            int k = (dy + R) * N + (dx + R);\n" +
            "            sads[k] = raw;\n" +
            "            float s = raw + 0.0002 * float(abs(v.x) + abs(v.y));\n" +
            "            if (s < best) {\n" +
            "                best = s;\n" +
            "                bi = k;\n" +
            "            }\n" +
            "        }\n" +
            "    }\n" +
            "    vec2 bestV = vec2(pred + ivec2(bi % N - R, bi / N - R));\n" +
            "    best = sads[bi];\n" +
            "    // Zero motion is the most common answer: take it unless the search is clearly better\n" +
            "    float zero = sadAt(o, ivec2(0));\n" +
            "    if (zero <= best + 0.0015) {\n" +
            "        outColor = vec4(0.0, 0.0, zero, 1.0);\n" +
            "        return;\n" +
            "    }\n" +
            "    // Sub-pixel refinement (parabola through the neighbours of the best match)\n" +
            "    int bx = bi % N;\n" +
            "    int by = bi / N;\n" +
            "    vec2 sub = vec2(0.0);\n" +
            "    if (bx > 0 && bx < N - 1) {\n" +
            "        float l = sads[by * N + bx - 1];\n" +
            "        float r = sads[by * N + bx + 1];\n" +
            "        float den = l - 2.0 * best + r;\n" +
            "        if (den > 1e-5) { sub.x = clamp(0.5 * (l - r) / den, -0.5, 0.5); }\n" +
            "    }\n" +
            "    if (by > 0 && by < N - 1) {\n" +
            "        float u = sads[(by - 1) * N + bx];\n" +
            "        float d = sads[(by + 1) * N + bx];\n" +
            "        float den = u - 2.0 * best + d;\n" +
            "        if (den > 1e-5) { sub.y = clamp(0.5 * (u - d) / den, -0.5, 0.5); }\n" +
            "    }\n" +
            "    outColor = vec4((bestV + sub) * 4.0, best, 1.0);\n" +
            "}\n";

    private static final String FS_MEDIAN =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "precision highp int;\n" +
            "uniform highp sampler2D uMv;\n" +
            "out vec4 outColor;\n" +
            "void sort2(inout float a, inout float b) { float t = min(a, b); b = max(a, b); a = t; }\n" +
            "float median9(float v[9]) {\n" +
            "    // Paeth's 9-element median network\n" +
            "    sort2(v[1], v[2]); sort2(v[4], v[5]); sort2(v[7], v[8]);\n" +
            "    sort2(v[0], v[1]); sort2(v[3], v[4]); sort2(v[6], v[7]);\n" +
            "    sort2(v[1], v[2]); sort2(v[4], v[5]); sort2(v[7], v[8]);\n" +
            "    sort2(v[0], v[3]); sort2(v[5], v[8]); sort2(v[4], v[7]);\n" +
            "    sort2(v[3], v[6]); sort2(v[1], v[4]); sort2(v[2], v[5]);\n" +
            "    sort2(v[4], v[7]); sort2(v[4], v[2]); sort2(v[6], v[4]);\n" +
            "    sort2(v[4], v[2]);\n" +
            "    return v[4];\n" +
            "}\n" +
            "void main() {\n" +
            "    ivec2 size = textureSize(uMv, 0);\n" +
            "    ivec2 p = ivec2(gl_FragCoord.xy);\n" +
            "    float xs[9];\n" +
            "    float ys[9];\n" +
            "    int k = 0;\n" +
            "    for (int j = -1; j <= 1; j++) {\n" +
            "        for (int i = -1; i <= 1; i++) {\n" +
            "            vec4 m = texelFetch(uMv, clamp(p + ivec2(i, j), ivec2(0), size - 1), 0);\n" +
            "            xs[k] = m.x;\n" +
            "            ys[k] = m.y;\n" +
            "            k++;\n" +
            "        }\n" +
            "    }\n" +
            "    vec4 center = texelFetch(uMv, p, 0);\n" +
            "    outColor = vec4(median9(xs), median9(ys), center.z, 1.0);\n" +
            "}\n";

    private static final String FS_INTERP =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "uniform sampler2D uPrevFrame;\n" +
            "uniform sampler2D uCurFrame;\n" +
            "uniform sampler2D uMv;\n" +
            "uniform float uAlpha;\n" +
            "uniform vec2 uTexel;\n" +
            "in vec2 vUv;\n" +
            "out vec4 outColor;\n" +
            "void main() {\n" +
            "    vec4 m = texture(uMv, vUv);\n" +
            "    vec2 v = m.xy * uTexel;\n" +
            "    vec4 cp = texture(uPrevFrame, vUv + uAlpha * v);\n" +
            "    vec4 cc = texture(uCurFrame, vUv - (1.0 - uAlpha) * v);\n" +
            "    vec4 blended = mix(cp, cc, uAlpha);\n" +
            "    // Where the two warped pictures disagree (things appearing/disappearing) or the match was\n" +
            "    // poor, show the newer frame instead of a smeared mix\n" +
            "    float disagree = smoothstep(0.08, 0.22, length(cp.rgb - cc.rgb));\n" +
            "    float unreliable = smoothstep(0.05, 0.12, m.z);\n" +
            "    float fallback = max(disagree, unreliable);\n" +
            "    vec4 safe = texture(uCurFrame, vUv - (1.0 - uAlpha) * v * (1.0 - unreliable));\n" +
            "    outColor = vec4(mix(blended, safe, fallback).rgb, 1.0);\n" +
            "}\n";

    private static final String FS_BLIT =
            "#version 300 es\n" +
            "precision mediump float;\n" +
            "uniform sampler2D uTex;\n" +
            "in vec2 vUv;\n" +
            "out vec4 outColor;\n" +
            "void main() {\n" +
            "    outColor = vec4(texture(uTex, vUv).rgb, 1.0);\n" +
            "}\n";

    private static final String VS_OES =
            "attribute vec2 aPos;\n" +
            "varying vec2 vUv;\n" +
            "void main() {\n" +
            "    vUv = aPos * 0.5 + 0.5;\n" +
            "    gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "}\n";

    private static final String FS_OES =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "uniform samplerExternalOES uTex;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "varying vec2 vUv;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(uTex, (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy);\n" +
            "}\n";
}
