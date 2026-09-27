package com.cookandroid.waterfall;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.MotionEvent;

public class SpectrogramSurfaceView extends GLSurfaceView {

    private final SpectrogramRenderer renderer;

    private float lastX, lastY;

    public SpectrogramSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        renderer = new SpectrogramRenderer(context);
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    public SpectrogramRenderer getRenderer() {
        return renderer;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        float y = e.getY();

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = x;
                lastY = y;
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = x - lastX;
                float dy = y - lastY;
                lastX = x;
                lastY = y;

                // 드래그 민감도(원하면 조절)
                float sensitivity = 0.25f;

                renderer.addRotation(dx * sensitivity, dy * sensitivity);
                return true;
        }
        return super.onTouchEvent(e);
    }
}
