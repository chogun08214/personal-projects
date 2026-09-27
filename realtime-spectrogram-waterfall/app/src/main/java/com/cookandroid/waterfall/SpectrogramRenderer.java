package com.cookandroid.waterfall;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class SpectrogramRenderer implements android.opengl.GLSurfaceView.Renderer {

    private final Context context;

    // ---- waterfall params ----
    private static final int TIME_DEPTH = 120; // 시간축(프레임) 쌓는 개수
    private int freqBins = 512;                // y축 bin 개수(입력 컬럼 길이에 맞춰짐)

    // spectrogram ring buffer: [timeIndex][freqBin] = db
    private float[][] specDb = new float[TIME_DEPTH][freqBins];
    private int writeIndex = 0;
    private volatile boolean hasData = false;

    // display controls
    private volatile float zGain = 1.0f;   // amplitude scaling
    private volatile int maxFreqHz = 5000; // analyzer에서 bin 수를 줄여서 보내면 자동 반영됨

    // ---- GL stuff ----
    private int program;
    private int aPosLoc, aColLoc;
    private int uMvpLoc;

    private FloatBuffer vtxBuf;
    private FloatBuffer colBuf;

    // matrices
    private final float[] proj = new float[16];
    private final float[] view = new float[16];
    private final float[] model = new float[16];
    private final float[] mvp = new float[16];

    // toggles (버튼용)
    private volatile boolean showWaterfall = true;
    private volatile boolean showCross = false; // 나중에 확장 가능(지금은 토글만)

    private volatile float rotY = 25f;   // 좌우 회전(yaw)
    private volatile float rotX = -20f;  // 상하 회전(pitch)

    public void setSampleRate(int sr) {
        // 지금 워터폴 렌더러는 sampleRate를 직접 안 쓰지만,
        // Analyzer에서 호출하므로 일단 받아두거나 무시해도 됨.
    }

    public SpectrogramRenderer(Context context) {
        this.context = context;
        // 초기 행렬
        Matrix.setIdentityM(model, 0);
    }

    // MainActivity/Analyzer에서 호출 가능하게
    public void toggleSpectrogram() { showWaterfall = !showWaterfall; }
    public void toggleCrossSection() { showCross = !showCross; }

    public void addRotation(float dYaw, float dPitch) {
        rotY += dYaw;
        rotX += dPitch;

        // 상하 회전은 너무 뒤집히지 않게 제한
        if (rotX > 60f) rotX = 60f;
        if (rotX < -60f) rotX = -60f;
    }

    public void setZGain(float gain) { this.zGain = gain; }

    /** STFT에서 들어온 1프레임(주파수별 dB) */
    public synchronized void pushSpectrogramColumn(float[] colDb) {
        if (colDb == null || colDb.length < 16) return;

        // freqBins 변경(사용자가 maxFreqHz를 바꾸면 analyzer가 bin 수를 바꿔서 보낼 수 있음)
        if (colDb.length != freqBins) {
            freqBins = colDb.length;
            specDb = new float[TIME_DEPTH][freqBins];
            writeIndex = 0;
        }

        System.arraycopy(colDb, 0, specDb[writeIndex], 0, freqBins);
        writeIndex = (writeIndex + 1) % TIME_DEPTH;
        hasData = true;
    }

    @Override
    public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl, javax.microedition.khronos.egl.EGLConfig config) {
        program = buildProgram(VS, FS);
        aPosLoc = GLES20.glGetAttribLocation(program, "aPos");
        aColLoc = GLES20.glGetAttribLocation(program, "aCol");
        uMvpLoc = GLES20.glGetUniformLocation(program, "uMVP");

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
    }

    @Override
    public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);

        float aspect = (height == 0) ? 1f : (float) width / (float) height;
        Matrix.perspectiveM(proj, 0, 45f, aspect, 0.1f, 100f);

        // 카메라(이거 조절하면 네가 올린 그림 각도처럼 맞춰짐)
        // eye(x,y,z), center(x,y,z), up(x,y,z)
        Matrix.setLookAtM(view, 0,
                2.3f, 1.6f, 3.8f,   // eye
                0.0f, 0.0f, 0.0f,   // center
                0.0f, 1.0f, 0.0f);  // up
    }

    @Override
    public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        if (!showWaterfall || !hasData) return;

        // 1) 최신 ring buffer를 vertex/color로 빌드
        buildWaterfallBuffers();

        if (vtxBuf == null || colBuf == null) return;

        // 2) MVP
        Matrix.setIdentityM(model, 0);
        // 약간 회전/스케일 주면 MATLAB 느낌 더 난다
        Matrix.rotateM(model, 0, rotX, 1f, 0f, 0f); // pitch
        Matrix.rotateM(model, 0, rotY, 0f, 1f, 0f); // yaw
        Matrix.scaleM(model, 0, 1.2f, 1.0f, 1.0f);

        float[] vp = new float[16];
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0);

        // 3) draw
        GLES20.glUseProgram(program);
        GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvp, 0);

        GLES20.glEnableVertexAttribArray(aPosLoc);
        GLES20.glVertexAttribPointer(aPosLoc, 3, GLES20.GL_FLOAT, false, 0, vtxBuf);

        GLES20.glEnableVertexAttribArray(aColLoc);
        GLES20.glVertexAttribPointer(aColLoc, 3, GLES20.GL_FLOAT, false, 0, colBuf);

        // 버퍼는 "연속된 삼각형 스트립들"을 한 번에 그리기 어렵기 때문에:
        // 시간 프레임(리본) 하나씩 GL_TRIANGLE_STRIP로 draw
        int vertsPerStrip = (freqBins * 2); // 각 bin당 2개(앞/뒤)
        int totalStrips = TIME_DEPTH;

        int base = 0;
        for (int t = 0; t < totalStrips; t++) {
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, base, vertsPerStrip);
            base += vertsPerStrip;
        }

        GLES20.glDisableVertexAttribArray(aPosLoc);
        GLES20.glDisableVertexAttribArray(aColLoc);
    }

    /**
     * 워터폴: 시간축으로 TIME_DEPTH개의 리본을 만든다.
     * 각 리본은 주파수축으로 quad strip:
     *  - (x_front, y_i, z_i)  와  (x_back, y_i, z_i)  두 점씩 이어서 STRIP 구성
     */
    private synchronized void buildWaterfallBuffers() {
        // 스케일링(보기 좋게 -1~1로 정규화)
        float yMin = 0f;
        float yMax = (float)(freqBins - 1);

        // 시간축 정규화
        float xSpan = 2.0f; // -1..+1 정도
        float xStep = xSpan / (TIME_DEPTH - 1);

        // 리본 두께(앞/뒤 x차이)
        float ribbonThickness = xStep * 0.85f;

        // dB 범위(컬러/높이 매핑)
        float dbMin = -80f;
        float dbMax = 0f;

        // vertex count
        int vertsPerStrip = freqBins * 2;
        int totalVerts = TIME_DEPTH * vertsPerStrip;

        float[] vtx = new float[totalVerts * 3]; // xyz
        float[] col = new float[totalVerts * 3]; // rgb

        // ring buffer에서 "가장 최신 프레임이 앞(가까운 쪽)"로 오게 순서 구성
        // 최신은 writeIndex-1
        for (int t = 0; t < TIME_DEPTH; t++) {
            int idx = (writeIndex - 1 - t);
            while (idx < 0) idx += TIME_DEPTH;
            idx %= TIME_DEPTH;

            // x: 앞쪽이 +1 근처, 뒤로 갈수록 -1
            float xFront = 1.0f - t * xStep;
            float xBack  = xFront - ribbonThickness;

            for (int f = 0; f < freqBins; f++) {
                float y = map(f, yMin, yMax, -1.0f, 1.0f); // freq -> [-1,1]

                float db = specDb[idx][f];
                // z 높이: dB를 0~1로 정규화 후 스케일
                float zN = clamp((db - dbMin) / (dbMax - dbMin), 0f, 1f);
                float z = zN * 1.6f * zGain; // 높이(원하면 조절)

                // 컬러맵(jet 비슷)
                float[] rgb = jet(zN);

                // STRIP에서 각 bin당 2점: front/back
                int baseVert = (t * vertsPerStrip + f * 2);

                // front vertex
                int vi0 = baseVert * 3;
                vtx[vi0]     = xFront;
                vtx[vi0 + 1] = y;
                vtx[vi0 + 2] = z;

                col[vi0]     = rgb[0];
                col[vi0 + 1] = rgb[1];
                col[vi0 + 2] = rgb[2];

                // back vertex
                int vi1 = (baseVert + 1) * 3;
                vtx[vi1]     = xBack;
                vtx[vi1 + 1] = y;
                vtx[vi1 + 2] = z;

                // back은 살짝 어둡게(입체감)
                col[vi1]     = rgb[0] * 0.85f;
                col[vi1 + 1] = rgb[1] * 0.85f;
                col[vi1 + 2] = rgb[2] * 0.85f;
            }
        }

        vtxBuf = makeFloatBuffer(vtx);
        colBuf = makeFloatBuffer(col);
    }

    private FloatBuffer makeFloatBuffer(float[] a) {
        ByteBuffer bb = ByteBuffer.allocateDirect(a.length * 4).order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(a).position(0);
        return fb;
    }

    private float map(float v, float inMin, float inMax, float outMin, float outMax) {
        if (inMax - inMin == 0f) return outMin;
        float t = (v - inMin) / (inMax - inMin);
        return outMin + t * (outMax - outMin);
    }

    private float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /**
     * 간단 jet 컬러맵(0..1 -> RGB)
     * 파랑 -> 시안 -> 초록 -> 노랑 -> 빨강 느낌
     */
    private float[] jet(float t) {
        float r = clamp(1.5f * t - 0.5f, 0f, 1f);
        float g = clamp(1.5f - Math.abs(2f * t - 1f), 0f, 1f);
        float b = clamp(1.5f * (1f - t) - 0.5f, 0f, 1f);
        return new float[]{r, g, b};
    }

    // -------- shaders / program helpers --------
    private int buildProgram(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        return p;
    }

    private int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        return s;
    }

    private static final String VS =
            "uniform mat4 uMVP;\n" +
                    "attribute vec3 aPos;\n" +
                    "attribute vec3 aCol;\n" +
                    "varying vec3 vCol;\n" +
                    "void main(){\n" +
                    "  vCol = aCol;\n" +
                    "  gl_Position = uMVP * vec4(aPos, 1.0);\n" +
                    "}\n";

    private static final String FS =
            "precision mediump float;\n" +
                    "varying vec3 vCol;\n" +
                    "void main(){\n" +
                    "  gl_FragColor = vec4(vCol, 1.0);\n" +
                    "}\n";
}
