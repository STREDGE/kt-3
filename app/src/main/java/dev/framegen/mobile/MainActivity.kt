package dev.framegen.mobile

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** One screen: device report, the three permissions, start/stop and the journal. */
class MainActivity : Activity() {

    private companion object {
        const val REQUEST_CAPTURE = 1
        const val REQUEST_NOTIFICATIONS = 2
    }

    private lateinit var reportView: TextView
    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        }
        setContentView(scroll)

        column.addView(text("FrameGen Mobile — тестовая сборка 0.1", 20f, bold = true))
        column.addView(
            text(
                "Эта сборка проверяет, подходит ли устройство для генерации кадров: захват экрана, " +
                    "окно поверх игры и простейшую вставку кадров смешиванием. Качество картинки " +
                    "в ней ещё не настоящее."
            )
        )

        column.addView(header("1. Устройство"))
        reportView = mono()
        column.addView(reportView)
        column.addView(button("Скопировать отчёт") { copyReport() })
        column.addView(button("Отправить отчёт…") { shareReport() })

        column.addView(header("2. Подготовка"))
        statusView = text("")
        column.addView(statusView)
        column.addView(button("Включить службу специальных возможностей") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        column.addView(
            text(
                "В списке выберите «FrameGen: окно поверх игры». Если переключатель серый: откройте " +
                    "«Настройки → Приложения → FrameGen Mobile», нажмите ⋮ в углу и «Разрешить " +
                    "ограниченные настройки», затем вернитесь сюда.",
                13f
            )
        )
        column.addView(button("Запасной вариант: окно поверх приложений") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        if (Build.VERSION.SDK_INT >= 33) {
            column.addView(button("Разрешить уведомления") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            })
        }

        column.addView(header("3. Запуск"))
        column.addView(
            text(
                "Нажмите «Старт». Если система спросит, что показывать, выберите «Одно приложение» " +
                    "и укажите игру. Затем откройте игру.\n\n" +
                    "Слева на экране появится круглая кнопка FG: нажатие переключает режим " +
                    "(Счётчик → Зеркало → Смешивание x2), удержание останавливает проверку.\n\n" +
                    "Поиграйте по минуте в каждом режиме, остановите и пришлите отчёт."
            )
        )
        column.addView(button("Старт") { start() })
        column.addView(button("Стоп") { stop() })

        column.addView(header("4. Журнал последнего запуска"))
        logView = mono()
        column.addView(logView)

        Thread {
            if (Diag.gpu.isEmpty()) Diag.gpu = DeviceReport.queryGpu()
            runOnUiThread { refresh() }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresher)
    }

    override fun onPause() {
        ui.removeCallbacks(refresher)
        super.onPause()
    }

    private fun refresh() {
        reportView.text = DeviceReport.build(this)
        val accessibility = PassthroughService.instance != null
        val overlay = Settings.canDrawOverlays(this)
        val notifications = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        statusView.text = buildString {
            append(mark(accessibility)).append(" Служба специальных возможностей: ")
            append(if (accessibility) "включена" else "выключена (нужна, чтобы касания проходили в игру)")
            append('\n')
            append(mark(overlay)).append(" Окно поверх приложений: ")
            append(if (overlay) "разрешено" else "не разрешено (нужно только без службы)")
            append('\n')
            append(mark(notifications)).append(" Уведомления: ")
            append(if (notifications) "разрешены" else "не разрешены (необязательно)")
            append('\n')
            append(if (CaptureService.running) "▶ Проверка идёт" else "■ Проверка не запущена")
        }
        val log = Diag.dump()
        logView.text = log.ifEmpty { "Пока пусто." }
    }

    private fun mark(ok: Boolean) = if (ok) "✓" else "✗"

    private fun start() {
        if (CaptureService.running) {
            toast("Проверка уже идёт")
            return
        }
        if (PassthroughService.instance == null && !Settings.canDrawOverlays(this)) {
            toast("Сначала включите службу специальных возможностей")
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    private fun stop() {
        if (!CaptureService.running) {
            toast("Проверка не запущена")
            return
        }
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            toast("Захват экрана не разрешён")
            return
        }
        Diag.clear()
        startForegroundService(
            Intent(this, CaptureService::class.java)
                .setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
        )
        toast("Запущено. Откройте игру.")
    }

    private fun fullReport() = buildString {
        append("FrameGen Mobile 0.1\n\n")
        append(DeviceReport.build(this@MainActivity))
        append("\n\nЖурнал:\n")
        append(Diag.dump().ifEmpty { "пусто" })
    }

    private fun copyReport() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("FrameGen", fullReport()))
        toast("Отчёт скопирован")
    }

    private fun shareReport() {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, fullReport())
        startActivity(Intent.createChooser(send, "Отправить отчёт"))
    }

    // ---- tiny view helpers ----

    private fun text(value: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun header(value: String) = text(value, 17f, bold = true).apply {
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun mono() = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun button(label: String, action: (View) -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener(action)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
