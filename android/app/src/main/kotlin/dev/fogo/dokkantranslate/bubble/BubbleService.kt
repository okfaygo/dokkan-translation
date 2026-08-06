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
import dev.fogo.dokkantranslate.api.Kit
import dev.fogo.dokkantranslate.ui.HistoryEntry
import dev.fogo.dokkantranslate.identify.CardIdentifier
import dev.fogo.dokkantranslate.identify.MatchDebug
import dev.fogo.dokkantranslate.identify.Outcome
import dev.fogo.dokkantranslate.match.CardIndex
import dev.fogo.dokkantranslate.match.IndexUpdater
import dev.fogo.dokkantranslate.ui.BubblePanel
import dev.fogo.dokkantranslate.ui.UiState
import dev.fogo.dokkantranslate.ui.toUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
// the CoroutineScope extension — NOT NonCancellable.isActive, which the IDE
// offers first and which is hardcoded to true
import kotlinx.coroutines.isActive
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
    private var panelParams: WindowManager.LayoutParams? = null
    private var capture: ScreenCapture? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var work: Job? = null

    private var state by mutableStateOf<UiState>(UiState.Idle)
    private var history by mutableStateOf<List<HistoryEntry>>(emptyList())
    private var panelCollapsed by mutableStateOf(false)
    /**
     * Experimental, and OFF by default. It works, but going card-to-card is
     * rare in practice, and each refresh costs a visible flicker: capturing
     * means hiding our own overlays first, which is inherent to the design
     * rather than a bug that can be polished out. Not worth a poll loop and
     * a flicker by default.
     */
    private var autoRefresh by mutableStateOf(false)
    private var panelVisible = false
    private var watch: Job? = null

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
                isRunning = true
                showBubble()
                // parse the 3.5MB index now rather than on the first tap,
                // after checking for a newer one (usually a 304)
                scope.launch(Dispatchers.IO) {
                    if (IndexUpdater.refresh(this@BubbleService)) {
                        CardIndex.invalidate()
                    }
                    CardIndex.load(this@BubbleService)
                }
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
        identifyScreen(auto = false)
    }

    /**
     * @param auto true when the watcher noticed the game moved to another
     *   card. Automatic passes are silent about failure: navigating to a
     *   menu must not wipe out the kit the user was reading.
     */
    private fun identifyScreen(auto: Boolean) {
        if (work?.isActive == true) return
        val previous = state
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

            if (!auto) {
                state = UiState.Working("Reading the screen…")
                // a tap means "show me this" — undo a collapse from last time
                if (panelCollapsed) applyPanelCollapsed(false)
                showPanel()
            }
            if (frame == null) {
                if (!auto) {
                    state = UiState.Failed(
                        "Couldn't read the screen. The projection may have been " +
                            "stopped — tap the notification to restart it."
                    )
                }
                return@launch
            }

            val outcome = CardIdentifier.identify(this@BubbleService, frame) { step ->
                if (!auto) state = UiState.Working(step)
            }
            frame.recycle()

            if (auto) {
                val success = outcome as? Outcome.Success
                val sameCard = success?.kit?.cardId ==
                    (previous as? UiState.Result)?.kit?.cardId
                // leave the panel alone unless we actually landed on a
                // different card — re-setting state would reset the scroll
                if (success == null || sameCard) {
                    state = previous
                    return@launch
                }
            }
            state = outcome.toUiState()
            (state as? UiState.Result)?.let { remember(it.kit) }
        }
    }

    /**
     * While the panel is open, notice when the game moves to another card
     * and re-identify. Scoped to "panel open" on purpose: the user asked
     * for this view, so refreshing it continues their request rather than
     * interrupting them — which is why blanket auto-detection was dropped.
     */
    private fun startWatching() {
        stopWatching()
        if (!autoRefresh) return
        watch = scope.launch {
            var baseline: IntArray? = null
            var changed = false
            var stable = 0
            while (isActive) {
                delay(WATCH_POLL_MS)
                if (!panelVisible || work?.isActive == true) {
                    baseline = null
                    continue
                }
                val sample = capture?.sampleRegion(panelParams?.height ?: 0)
                    ?: continue
                val base = baseline
                baseline = sample
                if (base == null) continue

                if (differs(base, sample)) {
                    // still moving (animation, scroll) — wait for it to land
                    changed = true
                    stable = 0
                } else if (changed) {
                    stable++
                    if (stable >= WATCH_STABLE_POLLS) {
                        changed = false
                        stable = 0
                        baseline = null
                        identifyScreen(auto = true)
                    }
                }
            }
        }
    }

    private fun stopWatching() {
        watch?.cancel()
        watch = null
    }

    /** Fraction of grid samples that moved appreciably. */
    private fun differs(a: IntArray, b: IntArray): Boolean {
        if (a.size != b.size || a.isEmpty()) return false
        var moved = 0
        for (i in a.indices) {
            if (kotlin.math.abs(a[i] - b[i]) > SAMPLE_TOLERANCE) moved++
        }
        return moved.toFloat() / a.size > CHANGED_FRACTION
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
                history = history,
                collapsed = panelCollapsed,
                autoRefresh = autoRefresh,
                onSelectCard = ::lookUp,
                onToggleCollapse = { applyPanelCollapsed(!panelCollapsed) },
                onToggleAutoRefresh = {
                    autoRefresh = !autoRefresh
                    if (autoRefresh) startWatching() else stopWatching()
                },
                onClose = { hidePanel() },
                onResume = {
                    hidePanel()
                    ProjectionRequestActivity.launch(this)
                },
            )
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            panelHeight(panelCollapsed),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        windowManager.addView(host.view, params)
        host.onShown()
        panelHost = host
        panelParams = params
        panelVisible = true
        startWatching()
    }

    /**
     * Collapsing resizes the WINDOW, not just its contents: an overlay that
     * still covered the lower screen would keep swallowing touches meant for
     * the game even with nothing drawn in it.
     *
     * Not named setPanelCollapsed — that is the JVM signature Kotlin already
     * generates for the `panelCollapsed` property's setter, so the two would
     * collide.
     */
    private fun applyPanelCollapsed(collapsed: Boolean) {
        panelCollapsed = collapsed
        val host = panelHost ?: return
        val params = panelParams ?: return
        params.height = panelHeight(collapsed)
        runCatching { windowManager.updateViewLayout(host.view, params) }
    }

    private fun panelHeight(collapsed: Boolean): Int =
        if (collapsed) (56 * resources.displayMetrics.density).toInt()
        else (resources.displayMetrics.heightPixels * 0.6f).toInt()

    private fun hidePanel() {
        panelVisible = false
        stopWatching()
        panelHost?.let { host ->
            runCatching { windowManager.removeView(host.view) }
            host.onRemoved()
        }
        panelHost = null
        panelParams = null
    }

    /** Newest first, de-duplicated, capped — cheap to revisit since kits
     *  are cached on disk permanently. */
    private fun remember(kit: Kit) {
        val entry = HistoryEntry(kit.cardId, kit.name)
        history = (listOf(entry) + history.filterNot { it.cardId == entry.cardId })
            .take(MAX_HISTORY)
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
            (state as? UiState.Result)?.let { remember(it.kit) }
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
        isRunning = false
        stopWatching()
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
        /** So the activity can show the real state — the bubble can also be
         *  stopped from the notification or by the system ending capture. */
        @Volatile
        var isRunning: Boolean = false
            private set

        const val ACTION_START = "dev.fogo.dokkantranslate.START_BUBBLE"
        const val ACTION_STOP = "dev.fogo.dokkantranslate.STOP_BUBBLE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "screen_reading"
        private const val NOTIFICATION_ID = 1
        private const val MAX_HISTORY = 8
        /** how often the watcher fingerprints the screen (cheap, no OCR) */
        private const val WATCH_POLL_MS = 700L
        /** consecutive quiet polls before treating a change as settled */
        private const val WATCH_STABLE_POLLS = 2
        private const val SAMPLE_TOLERANCE = 18
        private const val CHANGED_FRACTION = 0.12f
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
