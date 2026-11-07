package com.limelight.utils;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.Surface;

import com.limelight.preferences.PreferenceConfiguration;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Artemis Pocket: the AI SBS-3D renderer (OpenCV + LiteRT + MiDaS model, ~60 MB) was removed
 * because it has no use on a 5.5" handheld. This stub keeps the small API surface that the rest
 * of the app still references so upstream merges stay easy. Streams always render in 2D.
 */
public class Stereo3DRenderer implements GLSurfaceView.Renderer {

    public static boolean isMovieMode = false;
    public static volatile float fps = 0;
    public static volatile float threeDFps = 0;
    public static volatile float drawDelay = 0.0f;
    public static Boolean isDebugMode = false;
    public static Boolean isActive = false;
    public static String renderer = "CPU";

    public interface OnSurfaceReadyListener {
        void onStereo3DSurfaceReady(Surface surface);
    }

    public Stereo3DRenderer(GLSurfaceView view, OnSurfaceReadyListener listener, Context context, PreferenceConfiguration prefConfig) {
    }

    public void setPrefConfig(PreferenceConfiguration prefConfig) {
    }

    public void onSurfaceDestroyed() {
    }

    public Surface getVideoSurface() {
        return null;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
    }

    @Override
    public void onDrawFrame(GL10 gl) {
    }
}
