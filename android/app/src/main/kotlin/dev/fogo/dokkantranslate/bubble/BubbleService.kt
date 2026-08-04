package dev.fogo.dokkantranslate.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.fogo.dokkantranslate.MainActivity
import dev.fogo.dokkantranslate.R
import dev.fogo.dokkantranslate.identify.CardIdentifier
import dev.fogo.dokkantranslate.identify.MatchDebug
import dev.fogo.dokkantranslate.match.CardIndex
import dev.fogo.dokkantranslate.ui.BubblePanel
import dev.fogo.dokkantranslate.ui.UiState
import dev.fogo.dokkantranslate.ui.toUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Floating bubble over the game. Tap it and the current screen is read,
 * identified and shown in an overlay panel — no manual screenshot, and the
 * user never leaves Dokkan.
 */
class BubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubble: ImageView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelHost: OverlayComposeHost? = null
    private var capture: ScreenCapture? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null

    private var state by mutableStateOf<UiState>(UiState.Idle)
    private var panelVisible = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // startForeground() MUST come before getMediaProjection(),
                // or the system throws on Android 10+.
                startInForeground()
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                if (data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                capture?.release()
                capture = ScreenCapture.create(this, code, data) {
                    // system ended the projection (revoked, or screen locked)
                    scope.launch { onProjectionStopped() }
                }
                if (capture == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                showBubble()
                // parse the 3.5MB index now rather than on the first tap
                scope.launch(Dispatchers.Default) { CardIndex.load(this@BubbleService) }
            }
        }
        return START_NOT_STICKY
    }

    // ---- bubble ---------------------------------------------------------

    private fun showBubble() {
        if (bubble != null) return
        val size = (56 * resources.displayMetrics.density).toInt()
        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            alpha = 0.9f
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = resources.displayMetrics.heightPixels / 3
        }
        view.setOnTouchListener(DragTapListener(params) { onBubbleTapped() })
        windowManager.addView(view, params)
        bubble = view
        bubbleParams = params
    }

    /** Drag to move, tap to trigger — distinguished by touch slop. */
    private inner class DragTapListener(
        private val params: WindowManager.LayoutParams,
        private val onTap: () -> Unit,
    ) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@BubbleService).scaledTouchSlop
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var dragged = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragged = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) {
                        dragged = true
                    }
                    if (dragged) {
                        params.x = startX + dx
                        params.y = startY + dy
                        runCatching { windowManager.updateViewLayout(v, params) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragged) {
                    v.performClick()
                    onTap()
                }
            }
            return true
        }
    }

    private fun onBubbleTapped() {
        if (panelVisible) {
            hidePanel()
            return
        }
        if (work?.isActive == true) return
        work = scope.launch {
            // Our own bubble is ON the screen we are about to mirror, so hide
            // it first. Draining before the wait matters: buffered frames
            // block new ones, so without it we could capture a stale frame
            // that still shows the bubble (or an entirely different screen).
            setOverlaysVisible(false)
            capture?.drain()
            delay(FRAME_SETTLE_MS)
            val frame = capture?.captureLatest()
            setOverlaysVisible(true)

            state = UiState.Working("Reading the screen…")
            showPanel()
            state = if (frame == null) {
                UiState.Failed("Couldn't read the screen. The projection may have been stopped — tap the notification to restart it.")
            } else {
                CardIdentifier.identify(this@BubbleService, frame) { step ->
                    state = UiState.Working(step)
                }.toUiState().also { frame.recycle() }
            }
        }
    }

    private fun setOverlaysVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.INVISIBLE
        bubble?.visibility = visibility
        panelHost?.view?.visibility = visibility
    }

    private fun onProjectionStopped() {
        capture?.release()
        capture = null
        state = UiState.Failed(
            "Screen capture stopped (the screen locked, or you revoked it). " +
                "Tap Resume to grant it again."
        )
        showPanel()
    }

    // ---- result panel ---------------------------------------------------

    private fun showPanel() {
        if (panelHost != null) {
            panelVisible = true
            return
        }
        val host = OverlayComposeHost(this)
        host.setContent {
            BubblePanel(
                state = state,
                onSelectCard = ::lookUp,
                onClose = { hidePanel() },
                onResume = {
                    hidePanel()
                    ProjectionRequestActivity.launch(this)
                },
            )
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.6f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        windowManager.addView(host.view, params)
        host.onShown()
        panelHost = host
        panelVisible = true
    }

    private fun hidePanel() {
        panelVisible = false
        panelHost?.let { host ->
            runCatching { windowManager.removeView(host.view) }
            host.onRemoved()
        }
        panelHost = null
    }

    private fun lookUp(cardId: String) {
        val current = state as? UiState.Result
        work = scope.launch {
            state = UiState.Working("Fetching English kit…")
            state = CardIdentifier.lookUp(
                this@BubbleService,
                cardId,
                current?.alternatives ?: emptyList(),
                current?.debug ?: MatchDebug(),
            ).toUiState()
        }
    }

    // ---- foreground service plumbing ------------------------------------

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Screen reading",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Dokkan Translate is reading the screen")
            .setContentText("Tap the bubble over a card to identify it.")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        work?.cancel()
        scope.cancel()
        hidePanel()
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        capture?.release()
        capture = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.fogo.dokkantranslate.START_BUBBLE"
        const val ACTION_STOP = "dev.fogo.dokkantranslate.STOP_BUBBLE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "screen_reading"
        private const val NOTIFICATION_ID = 1
        /** let one clean frame (without our overlays) reach the reader */
        private const val FRAME_SETTLE_MS = 150L

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, BubbleService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BubbleService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
