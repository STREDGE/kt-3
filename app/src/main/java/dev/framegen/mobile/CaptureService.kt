package dev.framegen.mobile

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Foreground service that owns the screen capture, the overlay window and the GL pipeline
 * for one test session.
 */
class CaptureService : Service(), GlPipeline.Listener {

    companion object {
        const val ACTION_START = "dev.framegen.mobile.START"
        const val ACTION_STOP = "dev.framegen.mobile.STOP"
        const val ACTION_NEXT_MODE = "dev.framegen.mobile.NEXT_MODE"
        const val EXTRA_RESULT_CODE = "code"
        const val EXTRA_RESULT_DATA = "data"

        private const val CHANNEL = "framegen"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var running = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var pipeline: GlPipeline? = null

    private var overlayWm: WindowManager? = null
    private var overlayRoot: View? = null
    private var controlButton: View? = null
    private var hud: TextView? = null
    private var trusted = false
    private var skipScreenshot = "не проверялось"

    private var mode = GlPipeline.MODE_COUNTER
    private var displayW = 0
    private var displayH = 0
    private var capW = 0
    private var capH = 0
    private var dpi = 0
    private var hz = 60f
    private var contentSized = false
    private var stopping = false
    private var lastStats: GlPipeline.Stats? = null
    private var note = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_NEXT_MODE -> nextMode()
            ACTION_STOP -> stopEverything()
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }

    // ---- session ----

    private fun startCapture(intent: Intent) {
        if (running) return
        try {
            startForeground(
                NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } catch (t: Throwable) {
            Diag.log("Не удалось запустить службу: $t")
            stopSelf()
            return
        }
        running = true
        stopping = false

        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val mp = try {
            if (data == null) null
            else getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
        } catch (t: Throwable) {
            Diag.log("Система не дала захват экрана: $t")
            null
        }
        if (mp == null) {
            Diag.log("Захват экрана не получен")
            stopEverything()
            return
        }
        projection = mp
        mp.registerCallback(projectionCallback, main)

        readDisplay()
        capW = displayW
        capH = displayH
        Diag.log("Старт: экран ${displayW}×${displayH}, ${hz.roundToInt()} Гц")

        val p = GlPipeline(this)
        pipeline = p
        p.start(capW, capH, hz)
        showOverlay()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main)
        updateHud()
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Diag.log("Система остановила захват экрана")
            stopEverything()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width <= 0 || height <= 0 || (width == capW && height == capH)) return
            contentSized = true
            applyCaptureSize(width, height)
        }

        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            Diag.log(if (isVisible) "Захваченное приложение на экране" else "Захваченное приложение скрыто")
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || stopping) return
            val oldW = displayW
            val oldH = displayH
            val oldHz = hz
            readDisplay()
            if (hz != oldHz) pipeline?.setDisplayHz(hz)
            if (!contentSized && (displayW != oldW || displayH != oldH)) applyCaptureSize(displayW, displayH)
            updateHud()
        }
    }

    private fun applyCaptureSize(width: Int, height: Int) {
        capW = width
        capH = height
        try {
            virtualDisplay?.resize(width, height, dpi)
        } catch (t: Throwable) {
            Diag.log("Не удалось изменить размер захвата: $t")
        }
        pipeline?.resizeCapture(width, height)
    }

    private fun readDisplay() {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            displayW = bounds.width()
            displayH = bounds.height()
        } else {
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            displayW = size.x
            displayH = size.y
        }
        dpi = resources.configuration.densityDpi
        hz = display.refreshRate
    }

    // ---- GlPipeline.Listener (GL thread) ----

    override fun onCaptureSurfaceReady(surface: Surface) {
        main.post {
            val mp = projection ?: return@post
            try {
                virtualDisplay = mp.createVirtualDisplay(
                    "framegen", capW, capH, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
                )
                Diag.log("Захват запущен: ${capW}×${capH}")
                main.postDelayed({ pipeline?.startProbe() }, 1500)
            } catch (t: Throwable) {
                Diag.log("Не удалось начать захват: $t")
                stopEverything()
            }
        }
    }

    override fun onStats(stats: GlPipeline.Stats) {
        val pipelineText = if (stats.pipelineMs >= 0f) "%.1f".format(stats.pipelineMs) else "н/д"
        Diag.log(
            "${modeName()}: игра %.1f к/с, захват %.1f, показ %.1f, обработка %.1f мс, конвейер %s мс"
                .format(stats.uniqueFps, stats.captureFps, stats.presentFps, stats.processMs, pipelineText)
        )
        main.post {
            lastStats = stats
            updateHud()
        }
    }

    override fun onProbeResult(loop: Boolean) {
        main.post {
            note = if (loop) {
                "Захват видит наше окно. Режимы с картинкой отключены.\n" +
                    "Перезапустите и выберите «Одно приложение»."
            } else {
                "Проверка пройдена. Кнопка FG переключает режим."
            }
            updateHud()
        }
    }

    // ---- overlay ----

    private fun showOverlay() {
        val accessibility = PassthroughService.instance
        trusted = accessibility != null
        if (!trusted && !Settings.canDrawOverlays(this)) {
            Diag.log("Нет ни службы специальных возможностей, ни разрешения на окно: работаем без окна")
            return
        }
        val ctx: Context = accessibility ?: this
        val wm = ctx.getSystemService(WindowManager::class.java)
        val type = if (trusted) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }

        val root = FrameLayout(ctx)
        val surfaceView = SurfaceView(ctx)
        surfaceView.holder.setFormat(PixelFormat.TRANSLUCENT)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                tryExcludeFromCapture(surfaceView)
                pipeline?.attachWindow(holder.surface, width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                pipeline?.detachWindow()
            }
        })
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val hudView = TextView(ctx).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setBackgroundColor(0xAA000000.toInt())
            val pad = dp(6)
            setPadding(pad, pad, pad, pad)
        }
        root.addView(
            hudView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        )
        hud = hudView

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        // Android 12+ blocks touches through an ordinary overlay unless it is at most 80% opaque.
        if (!trusted) lp.alpha = 0.8f
        lp.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        fastestModeId()?.let { lp.preferredDisplayModeId = it }
        lp.title = "FrameGen overlay"

        try {
            wm.addView(root, lp)
            overlayWm = wm
            overlayRoot = root
            Diag.log("Окно поверх игры: ${if (trusted) "доверенное (специальные возможности)" else "обычное, непрозрачность 80%"}")
        } catch (t: Throwable) {
            Diag.log("Не удалось показать окно: $t")
            hud = null
            return
        }

        val button = TextView(ctx).apply {
            text = "FG"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC16A085.toInt())
            }
            setOnClickListener { nextMode() }
            setOnLongClickListener {
                stopEverything()
                true
            }
        }
        val blp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        blp.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        blp.x = dp(4)
        blp.title = "FrameGen button"
        try {
            wm.addView(button, blp)
            controlButton = button
        } catch (t: Throwable) {
            Diag.log("Не удалось показать кнопку: $t")
        }
    }

    /**
     * Asks the compositor to leave our frames out of screen captures, so the capture keeps seeing
     * the game and not our own output. This is a non-public call; where it is unavailable the
     * loop probe reports it and the user picks single-app capture instead.
     */
    private fun tryExcludeFromCapture(view: SurfaceView) {
        skipScreenshot = try {
            val control = view.surfaceControl
            val transaction = SurfaceControl.Transaction()
            val method = SurfaceControl.Transaction::class.java.getMethod(
                "setSkipScreenshot", SurfaceControl::class.java, Boolean::class.javaPrimitiveType
            )
            method.invoke(transaction, control, true)
            transaction.apply()
            "да"
        } catch (t: Throwable) {
            "нет (${t.javaClass.simpleName})"
        }
        Diag.log("Исключение окна из захвата: $skipScreenshot")
    }

    private fun fastestModeId(): Int? {
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val current = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate }
            ?.modeId
    }

    private fun nextMode() {
        val p = pipeline ?: return
        if (!p.probeDone) {
            note = "Идёт проверка захвата, подождите пару секунд."
        } else if (p.loopDetected) {
            mode = GlPipeline.MODE_COUNTER
            note = "Захват видит наше окно. Режимы с картинкой отключены.\n" +
                "Перезапустите и выберите «Одно приложение»."
            p.setMode(mode)
        } else {
            mode = (mode + 1) % 3
            note = ""
            p.setMode(mode)
            Diag.log("Режим: ${modeName()}")
        }
        updateHud()
    }

    private fun modeName() = when (mode) {
        GlPipeline.MODE_MIRROR -> "Зеркало"
        GlPipeline.MODE_BLEND -> "Смешивание x2"
        else -> "Счётчик"
    }

    private fun updateHud() {
        val view = hud ?: return
        val s = lastStats
        val p = pipeline
        val sb = StringBuilder()
        sb.append("FrameGen 0.1 · ").append(modeName()).append('\n')
        sb.append("экран ${hz.roundToInt()} Гц · захват ${capW}×${capH}\n")
        if (s != null) {
            sb.append("игра %.0f к/с · захват %.0f · показ %.0f\n".format(s.uniqueFps, s.captureFps, s.presentFps))
            val pipelineText = if (s.pipelineMs >= 0f) "%.1f мс".format(s.pipelineMs) else "н/д"
            sb.append("обработка %.1f мс · конвейер %s\n".format(s.processMs, pipelineText))
            if (mode == GlPipeline.MODE_BLEND) {
                sb.append(
                    if (s.doubling) "удвоение: да\n"
                    else "удвоение: нет, экран должен быть вдвое быстрее игры\n"
                )
            }
        }
        val loop = when {
            p == null || !p.probeDone -> "проверка…"
            p.loopDetected -> "ЕСТЬ"
            else -> "нет"
        }
        sb.append("петля: $loop · окно: ${if (trusted) "доверенное" else "обычное 80%"}")
        if (note.isNotEmpty()) sb.append('\n').append(note)
        view.text = sb
    }

    // ---- shutdown ----

    private fun stopEverything() {
        if (stopping) return
        stopping = true
        main.removeCallbacksAndMessages(null)
        try {
            getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        } catch (_: Throwable) {
        }
        val wm = overlayWm
        if (wm != null) {
            controlButton?.let { runCatching { wm.removeView(it) } }
            overlayRoot?.let { runCatching { wm.removeView(it) } }
        }
        controlButton = null
        overlayRoot = null
        overlayWm = null
        hud = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        projection?.let {
            runCatching { it.unregisterCallback(projectionCallback) }
            runCatching { it.stop() }
        }
        projection = null
        pipeline?.release()
        pipeline = null
        if (running) Diag.log("Остановлено")
        running = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    // ---- helpers ----

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Генерация кадров", NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), flags
        )
        val next = PendingIntent.getService(
            this, 2, Intent(this, CaptureService::class.java).setAction(ACTION_NEXT_MODE), flags
        )
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), flags)
        val noIcon: Icon? = null
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("FrameGen: идёт проверка")
            .setContentText("Кнопка FG на экране: нажатие — режим, удержание — стоп")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(noIcon, "Режим", next).build())
            .addAction(Notification.Action.Builder(noIcon, "Стоп", stop).build())
            .build()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
