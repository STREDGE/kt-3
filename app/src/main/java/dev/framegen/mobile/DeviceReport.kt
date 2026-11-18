package dev.framegen.mobile

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.view.Display
import kotlin.math.roundToInt

/** Everything about the device that decides whether frame generation can work on it. */
object DeviceReport {

    fun build(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("Устройство: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
        val soc = if (Build.VERSION.SDK_INT >= 31) {
            "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
        } else {
            "${Build.HARDWARE} / ${Build.BOARD}"
        }
        sb.append("Чип: $soc\n")
        sb.append("Графика: ${Diag.gpu.ifEmpty { "определяется…" }}\n")
        sb.append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")

        val display = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            val mode = display.mode
            sb.append(
                "Экран сейчас: ${mode.physicalWidth}×${mode.physicalHeight}, " +
                    "${mode.refreshRate.roundToInt()} Гц\n"
            )
            val modes = display.supportedModes
                .map { "${it.physicalWidth}×${it.physicalHeight}@${it.refreshRate.roundToInt()}" }
                .distinct()
                .joinToString(", ")
            sb.append("Режимы экрана: $modes\n")
        }

        val pm = ctx.packageManager
        val vulkan = when {
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x403000) -> "1.3"
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x402000) -> "1.2"
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x401000) -> "1.1"
            pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION) -> "1.0"
            else -> "нет"
        }
        sb.append("Vulkan: $vulkan\n")

        val am = ctx.getSystemService(ActivityManager::class.java)
        sb.append("OpenGL ES: ${am.deviceConfigurationInfo.glEsVersion}\n")
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        sb.append("Память: ${"%.1f".format(mem.totalMem / (1024.0 * 1024.0 * 1024.0))} ГБ\n")
        sb.append("Архитектура: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
        return sb.toString()
    }

    /** Reads the GPU name through a throwaway off-screen GL context. Call off the main thread. */
    fun queryGpu(): String {
        return try {
            val dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(dpy, version, 0, version, 1)
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            EGL14.eglChooseConfig(
                dpy,
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE
                ),
                0, configs, 0, 1, count, 0
            )
            if (count[0] == 0) return "не удалось определить"
            val ctx = EGL14.eglCreateContext(
                dpy, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            val surf = EGL14.eglCreatePbufferSurface(
                dpy, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            EGL14.eglMakeCurrent(dpy, surf, surf, ctx)
            val name = "${GLES20.glGetString(GLES20.GL_RENDERER)} · ${GLES20.glGetString(GLES20.GL_VERSION)}"
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(dpy, surf)
            EGL14.eglDestroyContext(dpy, ctx)
            name
        } catch (t: Throwable) {
            "не удалось определить (${t.javaClass.simpleName})"
        }
    }
}
