package dev.framegen.mobile

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * Owns the GL thread: receives captured screen frames, drops duplicates, keeps the last two
 * real frames and draws the result into the overlay window.
 *
 * Every public method may be called from any thread; the work is posted to the GL thread.
 */
class GlPipeline(private val listener: Listener) {

    interface Listener {
        /** GL thread. The surface the virtual display must render into. */
        fun onCaptureSurfaceReady(surface: Surface)

        /** GL thread, once a second. */
        fun onStats(stats: Stats)

        /** GL thread. True when the capture contains our own overlay. */
        fun onProbeResult(loop: Boolean)
    }

    class Stats(
        val captureFps: Float,
        val uniqueFps: Float,
        val presentFps: Float,
        val processMs: Float,
        val pipelineMs: Float,
        val gameIntervalMs: Float,
        val doubling: Boolean,
        val captureWidth: Int,
        val captureHeight: Int,
    )

    companion object {
        const val MODE_COUNTER = 0
        const val MODE_MIRROR = 1
        const val MODE_BLEND = 2

        private const val SIG = 32
        private const val DUP_THRESHOLD = 64

        private const val VS_OES = """
attribute vec2 aPos;
uniform mat4 uTexM;
varying vec2 vUv;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vUv = (uTexM * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;
}
"""
        private const val VS_PLAIN = """
attribute vec2 aPos;
varying vec2 vUv;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vUv = aPos * 0.5 + 0.5;
}
"""
        private const val FS_OES = """#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTex;
varying vec2 vUv;
void main() {
    gl_FragColor = vec4(texture2D(uTex, vUv).rgb, 1.0);
}
"""
        private const val FS_BLEND = """
precision mediump float;
uniform sampler2D uA;
uniform sampler2D uB;
uniform float uT;
varying vec2 vUv;
void main() {
    vec3 a = texture2D(uA, vUv).rgb;
    vec3 b = texture2D(uB, vUv).rgb;
    gl_FragColor = vec4(mix(a, b, uT), 1.0);
}
"""
        private const val FS_SOLID = """
precision mediump float;
uniform vec4 uColor;
void main() {
    gl_FragColor = uColor;
}
"""
    }

    private val thread = HandlerThread("fg-gl", Process.THREAD_PRIORITY_DISPLAY)
    private lateinit var handler: Handler

    @Volatile
    var loopDetected = false
        private set

    @Volatile
    var probeDone = false
        private set

    // ---- GL-thread state ----
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var winSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var winW = 0
    private var winH = 0

    private var capW = 0
    private var capH = 0
    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var captureSurface: Surface? = null
    private val texMatrix = FloatArray(16)

    private var progOes = 0
    private var progBlend = 0
    private var progSolid = 0
    private var oesPos = 0
    private var oesTexM = 0
    private var oesSampler = 0
    private var blendPos = 0
    private var blendA = 0
    private var blendB = 0
    private var blendT = 0
    private var solidPos = 0
    private var solidColor = 0

    private val fbo = IntArray(2)
    private val tex = IntArray(2)
    private var cur = 0
    private var frames = 0

    private var sigFbo = 0
    private var sigTex = 0
    private val sigBuf: ByteBuffer = ByteBuffer.allocateDirect(SIG * SIG * 4).order(ByteOrder.nativeOrder())
    private val sigNow = ByteArray(SIG * SIG * 4)
    private val sigPrev = ByteArray(SIG * SIG * 4)
    private var sigValid = false

    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            position(0)
        }

    private var mode = MODE_COUNTER
    private var released = false
    private var displayPeriodNs = 16_666_667L
    private var lastUniqueNs = 0L
    private var intervalNs = 0L
    private var doubling = false

    private var probing = false
    private var probeHits = 0
    private var probeTries = 0
    private var errorCount = 0

    private var statStartNs = 0L
    private var capCount = 0
    private var uniqCount = 0
    private var presentCount = 0
    private var procSumNs = 0L
    private var latSumNs = 0L
    private var latCount = 0

    // ---- public API ----

    fun start(width: Int, height: Int, refreshHz: Float) {
        thread.start()
        handler = Handler(thread.looper)
        handler.post {
            try {
                initEgl()
                initGl()
                createCapture(width, height)
                displayPeriodNs = (1_000_000_000.0 / max(refreshHz, 1f)).toLong()
                statStartNs = System.nanoTime()
                handler.postDelayed(statsTick, 1000)
                listener.onCaptureSurfaceReady(captureSurface!!)
            } catch (t: Throwable) {
                Diag.log("Ошибка запуска графики: $t")
            }
        }
    }

    fun attachWindow(surface: Surface, width: Int, height: Int) {
        if (!alive()) return
        handler.post {
            if (released || eglDisplay == EGL14.EGL_NO_DISPLAY) return@post
            destroyWindowSurface()
            val s = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (s == null || s == EGL14.EGL_NO_SURFACE) {
                Diag.log("Не удалось создать поверхность окна: 0x${Integer.toHexString(EGL14.eglGetError())}")
                return@post
            }
            winSurface = s
            EGL14.eglMakeCurrent(eglDisplay, winSurface, winSurface, eglContext)
            EGL14.eglSwapInterval(eglDisplay, 0)
            winW = width
            winH = height
            Diag.log("Окно готово: ${width}×${height}")
            redraw()
        }
    }

    /** Blocks briefly so the surface is not destroyed under a draw call. */
    fun detachWindow() {
        if (!alive()) return
        val latch = CountDownLatch(1)
        val posted = handler.post {
            destroyWindowSurface()
            latch.countDown()
        }
        if (posted) latch.await(500, TimeUnit.MILLISECONDS)
    }

    fun setMode(newMode: Int) {
        if (!alive()) return
        handler.post {
            mode = newMode
            doubling = false
            handler.removeCallbacks(showReal)
            redraw()
        }
    }

    fun setDisplayHz(refreshHz: Float) {
        if (!alive()) return
        handler.post { displayPeriodNs = (1_000_000_000.0 / max(refreshHz, 1f)).toLong() }
    }

    fun resizeCapture(width: Int, height: Int) {
        if (!alive()) return
        handler.post {
            if (released || (width == capW && height == capH)) return@post
            surfaceTexture?.setDefaultBufferSize(width, height)
            allocFrames(width, height)
            Diag.log("Размер захвата: ${width}×${height}")
        }
    }

    /** Flashes a solid colour in the overlay and checks whether it shows up in the capture. */
    fun startProbe() {
        if (!alive()) return
        handler.post { beginProbe() }
    }

    fun release() {
        if (!alive()) return
        handler.post {
            released = true
            handler.removeCallbacksAndMessages(null)
            try {
                surfaceTexture?.setOnFrameAvailableListener(null)
                captureSurface?.release()
                surfaceTexture?.release()
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                    )
                    if (winSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, winSurface)
                    if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbuffer)
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                }
            } catch (t: Throwable) {
                Diag.log("Ошибка при остановке графики: $t")
            }
            winSurface = EGL14.EGL_NO_SURFACE
            thread.quitSafely()
        }
    }

    // ---- GL thread ----

    private fun alive() = ::handler.isInitialized && thread.isAlive && !released

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(
            eglDisplay,
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            ),
            0, configs, 0, 1, count, 0
        )
        check(count[0] > 0) { "eglChooseConfig" }
        eglConfig = configs[0]
        eglContext = EGL14.eglCreateContext(
            eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        check(eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        pbuffer = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        check(EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)) { "eglMakeCurrent" }
    }

    private fun initGl() {
        val renderer = "${GLES20.glGetString(GLES20.GL_RENDERER)} · ${GLES20.glGetString(GLES20.GL_VERSION)}"
        Diag.gpu = renderer
        Diag.log("Графика: $renderer")

        progOes = link(VS_OES, FS_OES)
        oesPos = GLES20.glGetAttribLocation(progOes, "aPos")
        oesTexM = GLES20.glGetUniformLocation(progOes, "uTexM")
        oesSampler = GLES20.glGetUniformLocation(progOes, "uTex")

        progBlend = link(VS_PLAIN, FS_BLEND)
        blendPos = GLES20.glGetAttribLocation(progBlend, "aPos")
        blendA = GLES20.glGetUniformLocation(progBlend, "uA")
        blendB = GLES20.glGetUniformLocation(progBlend, "uB")
        blendT = GLES20.glGetUniformLocation(progBlend, "uT")

        progSolid = link(VS_PLAIN, FS_SOLID)
        solidPos = GLES20.glGetAttribLocation(progSolid, "aPos")
        solidColor = GLES20.glGetUniformLocation(progSolid, "uColor")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        sigTex = ids[0]
        initTexture(sigTex, SIG, SIG)
        GLES20.glGenFramebuffers(1, ids, 0)
        sigFbo = ids[0]
        attach(sigFbo, sigTex)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    private fun createCapture(width: Int, height: Int) {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        oesTex = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val st = SurfaceTexture(oesTex)
        st.setDefaultBufferSize(width, height)
        st.setOnFrameAvailableListener({ onFrame() }, handler)
        surfaceTexture = st
        captureSurface = Surface(st)
        allocFrames(width, height)
    }

    private fun allocFrames(width: Int, height: Int) {
        if (tex[0] != 0) {
            GLES20.glDeleteTextures(2, tex, 0)
            GLES20.glDeleteFramebuffers(2, fbo, 0)
        }
        GLES20.glGenTextures(2, tex, 0)
        GLES20.glGenFramebuffers(2, fbo, 0)
        for (i in 0..1) {
            initTexture(tex[i], width, height)
            attach(fbo[i], tex[i])
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        capW = width
        capH = height
        cur = 0
        frames = 0
        sigValid = false
        lastUniqueNs = 0L
        intervalNs = 0L
    }

    private fun initTexture(id: Int, width: Int, height: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun attach(framebuffer: Int, texture: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texture, 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Diag.log("Буфер кадра не готов: 0x${Integer.toHexString(status)}")
        }
    }

    private fun onFrame() {
        if (released) return
        val st = surfaceTexture ?: return
        try {
            val t0 = System.nanoTime()
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
            val stamp = st.timestamp
            capCount++

            readSignature()
            if (probing) {
                if (looksLikeProbe()) probeHits++
                return
            }
            val duplicate = sigValid && signatureDiff() < DUP_THRESHOLD
            System.arraycopy(sigNow, 0, sigPrev, 0, sigNow.size)
            sigValid = true
            if (duplicate) return

            uniqCount++
            if (lastUniqueNs != 0L) {
                val d = t0 - lastUniqueNs
                if (d in 1L until 250_000_000L) {
                    intervalNs = if (intervalNs == 0L) d else (intervalNs * 9 + d) / 10
                }
            }
            lastUniqueNs = t0

            // Keep the frame: the external texture only ever holds the newest one.
            val next = 1 - cur
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[next])
            GLES20.glViewport(0, 0, capW, capH)
            drawOes()
            cur = next
            if (frames < 2) frames++

            if (winSurface != EGL14.EGL_NO_SURFACE) {
                when (mode) {
                    MODE_MIRROR -> {
                        toWindow()
                        drawBlend(tex[cur], tex[cur], 1f)
                        present()
                    }

                    MODE_BLEND -> {
                        handler.removeCallbacks(showReal)
                        if (frames >= 2 && canDouble()) {
                            // In-between frame first, the real one half a game frame later.
                            doubling = true
                            toWindow()
                            drawBlend(tex[1 - cur], tex[cur], 0.5f)
                            present()
                            handler.postDelayed(showReal, max(1L, intervalNs / 2_000_000L))
                        } else {
                            doubling = false
                            toWindow()
                            drawBlend(tex[cur], tex[cur], 1f)
                            present()
                        }
                    }
                }
            }

            val t1 = System.nanoTime()
            procSumNs += t1 - t0
            val lat = t1 - stamp
            if (stamp > 0L && lat in 0L until 1_000_000_000L) {
                latSumNs += lat
                latCount++
            }
        } catch (t: Throwable) {
            if (errorCount++ < 5) Diag.log("Ошибка обработки кадра: $t")
        }
    }

    private val showReal = Runnable {
        if (!released && mode == MODE_BLEND && winSurface != EGL14.EGL_NO_SURFACE && frames > 0) {
            toWindow()
            drawBlend(tex[cur], tex[cur], 1f)
            present()
        }
    }

    /** The screen must refresh at least ~2x faster than the game for an extra frame to fit. */
    private fun canDouble() = intervalNs > 0L && intervalNs * 10 >= displayPeriodNs * 16

    private fun redraw() {
        if (winSurface == EGL14.EGL_NO_SURFACE) return
        toWindow()
        if (mode == MODE_COUNTER || frames == 0) {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        } else {
            drawBlend(tex[cur], tex[cur], 1f)
        }
        present()
    }

    private fun beginProbe() {
        if (released) return
        if (winSurface == EGL14.EGL_NO_SURFACE) {
            if (probeTries++ < 20) {
                handler.postDelayed({ beginProbe() }, 500)
            } else {
                probeDone = true
                Diag.log("Проверка петли не выполнена: окно не появилось")
                listener.onProbeResult(false)
            }
            return
        }
        probing = true
        probeHits = 0
        toWindow()
        GLES20.glUseProgram(progSolid)
        GLES20.glUniform4f(solidColor, 1f, 0f, 1f, 1f)
        drawQuad(solidPos)
        present()
        handler.postDelayed({ endProbe() }, 350)
    }

    private fun endProbe() {
        if (released) return
        probing = false
        // One hit is enough: on a static screen the flash produces a single captured frame.
        loopDetected = probeHits >= 1
        probeDone = true
        sigValid = false
        Diag.log("Проверка петли: кадров с нашим окном $probeHits → петля ${if (loopDetected) "ЕСТЬ" else "нет"}")
        redraw()
        listener.onProbeResult(loopDetected)
    }

    private fun readSignature() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sigFbo)
        GLES20.glViewport(0, 0, SIG, SIG)
        drawOes()
        sigBuf.position(0)
        GLES20.glReadPixels(0, 0, SIG, SIG, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, sigBuf)
        sigBuf.position(0)
        sigBuf.get(sigNow)
    }

    private fun signatureDiff(): Int {
        var sum = 0
        var i = 0
        while (i < sigNow.size) {
            sum += abs((sigNow[i].toInt() and 0xFF) - (sigPrev[i].toInt() and 0xFF))
            sum += abs((sigNow[i + 1].toInt() and 0xFF) - (sigPrev[i + 1].toInt() and 0xFF))
            sum += abs((sigNow[i + 2].toInt() and 0xFF) - (sigPrev[i + 2].toInt() and 0xFF))
            if (sum >= DUP_THRESHOLD) return sum
            i += 4
        }
        return sum
    }

    /** Magenta, allowing for the 80% window opacity of the fallback overlay. */
    private fun looksLikeProbe(): Boolean {
        var hits = 0
        var i = 0
        while (i < sigNow.size) {
            val r = sigNow[i].toInt() and 0xFF
            val g = sigNow[i + 1].toInt() and 0xFF
            val b = sigNow[i + 2].toInt() and 0xFF
            if (r > 180 && g < 90 && b > 180) hits++
            i += 4
        }
        return hits * 10 >= SIG * SIG * 7
    }

    private fun toWindow() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, winW, winH)
    }

    private fun present() {
        if (winSurface == EGL14.EGL_NO_SURFACE) return
        if (!EGL14.eglSwapBuffers(eglDisplay, winSurface)) {
            if (errorCount++ < 5) Diag.log("Ошибка вывода кадра: 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
        presentCount++
    }

    private fun destroyWindowSurface() {
        if (winSurface == EGL14.EGL_NO_SURFACE || eglDisplay == EGL14.EGL_NO_DISPLAY) return
        handler.removeCallbacks(showReal)
        EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)
        EGL14.eglDestroySurface(eglDisplay, winSurface)
        winSurface = EGL14.EGL_NO_SURFACE
    }

    private fun drawOes() {
        GLES20.glUseProgram(progOes)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(oesSampler, 0)
        GLES20.glUniformMatrix4fv(oesTexM, 1, false, texMatrix, 0)
        drawQuad(oesPos)
    }

    private fun drawBlend(a: Int, b: Int, t: Float) {
        GLES20.glUseProgram(progBlend)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, a)
        GLES20.glUniform1i(blendA, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, b)
        GLES20.glUniform1i(blendB, 1)
        GLES20.glUniform1f(blendT, t)
        drawQuad(blendPos)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    }

    private fun drawQuad(attribute: Int) {
        quad.position(0)
        GLES20.glEnableVertexAttribArray(attribute)
        GLES20.glVertexAttribPointer(attribute, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private val statsTick = object : Runnable {
        override fun run() {
            if (released) return
            val now = System.nanoTime()
            val sec = (now - statStartNs) / 1_000_000_000f
            if (sec > 0f) {
                listener.onStats(
                    Stats(
                        captureFps = capCount / sec,
                        uniqueFps = uniqCount / sec,
                        presentFps = presentCount / sec,
                        processMs = if (uniqCount > 0) procSumNs / uniqCount / 1_000_000f else 0f,
                        pipelineMs = if (latCount > 0) latSumNs / latCount / 1_000_000f else -1f,
                        gameIntervalMs = intervalNs / 1_000_000f,
                        doubling = doubling,
                        captureWidth = capW,
                        captureHeight = capH,
                    )
                )
            }
            statStartNs = now
            capCount = 0
            uniqCount = 0
            presentCount = 0
            procSumNs = 0L
            latSumNs = 0L
            latCount = 0
            handler.postDelayed(this, 1000)
        }
    }

    private fun link(vertex: String, fragment: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("link: $log")
        }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source.trimStart())
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("shader: $log")
        }
        return shader
    }
}
