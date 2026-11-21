package pl.kamil.hatv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Dashboard Home Assistanta – dokładnie taki jak na PC – w przeglądarce wbudowanej w aplikację.
 * Logowanie tokenem (tryb „external auth”, jak w oficjalnej aplikacji HA).
 *
 *  strzałki      – kursor (przytrzymanie przyspiesza; przy krawędzi przewija stronę)
 *  OK            – kliknięcie (przytrzymanie = długie naciśnięcie)
 *  P+ / P−       – poprzednia / następna karta (widok)
 *  1–9           – karta o tym numerze
 *  ⏪ ⏩          – przewijanie strony
 *  WSTECZ        – zamknięcie okienka / powrót do kafelków
 */
class DashboardActivity : Activity() {

    private val app get() = App.instance
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var web: WebView
    private lateinit var cursor: CursorView
    private lateinit var banner: TextView
    private var path = "lovelace"
    private var views: List<Pair<String, String>> = emptyList()
    private var viewIndex = 0
    private var cx = 0f
    private var cy = 0f
    private var touchDown = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger(this)
        path = intent.getStringExtra("path") ?: "lovelace"

        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#111111")) }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            setInitialScale(app.config.zoom)
            isFocusable = true
            isFocusableInTouchMode = true
            addJavascriptInterface(ExternalApp(), "externalApp")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                    if (req.isForMainFrame) showBanner("Nie można otworzyć Home Assistanta: ${err.description}", 8000)
                }
            }
        }
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        cursor = CursorView(this)
        root.addView(cursor, FrameLayout.LayoutParams(dp(34f), dp(40f)))

        banner = TextView(this).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(22f), dp(10f), dp(22f), dp(10f))
            background = GradientDrawable().apply { cornerRadius = dp(12f).toFloat(); setColor(Color.parseColor("#E00F2A3F")) }
            visibility = View.GONE
        }
        root.addView(banner, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(16f) })

        setContentView(root)

        val url = app.config.url
        if (url.isEmpty() || app.config.token.isEmpty()) {
            showBanner("Najpierw wpisz adres i token HA na stronie ustawień", 10000)
        } else {
            web.loadUrl("$url/$path?external_auth=1")
        }
        app.ha.listViews(path) { v -> views = v }

        root.post {
            cx = root.width / 2f; cy = root.height / 2f
            placeCursor()
            showBanner((intent.getStringExtra("title") ?: "Dashboard") + " · P+/P− karty · OK klik", 4000)
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    // ---------------- logowanie tokenem (protokół aplikacji HA) ----------------

    inner class ExternalApp {
        @JavascriptInterface
        fun getExternalAuth(payload: String) {
            val cb = try { JSONObject(payload).optString("callback") } catch (e: Exception) { "" }
            if (cb.isEmpty()) return
            val token = app.config.token
            val auth = JSONObject().put("access_token", token).put("expires_in", 1800)
            handler.post { web.evaluateJavascript("window.$cb(true, $auth);", null) }
        }

        @JavascriptInterface
        fun revokeExternalAuth(payload: String) {
            val cb = try { JSONObject(payload).optString("callback") } catch (e: Exception) { "" }
            if (cb.isNotEmpty()) handler.post { web.evaluateJavascript("window.$cb(true);", null) }
        }

        @JavascriptInterface
        fun externalBus(message: String) {
            val m = try { JSONObject(message) } catch (e: Exception) { return }
            if (m.optString("type") == "config/get") {
                val result = JSONObject()
                    .put("hasSettingsScreen", false).put("canWriteTag", false).put("hasExoPlayer", false)
                    .put("canCommissionMatter", false).put("canImportThreadCredentials", false)
                    .put("hasAssist", false).put("hasBarCodeScanner", 0)
                val resp = JSONObject().put("id", m.optInt("id")).put("type", "result").put("success", true).put("result", result)
                handler.post { web.evaluateJavascript("window.externalBus($resp);", null) }
            }
        }
    }

    // ---------------- kursor ----------------

    private fun placeCursor() {
        cursor.translationX = cx
        cursor.translationY = cy
        cursor.alpha = 1f
        cursor.animate().cancel()
        cursor.animate().alpha(0.25f).setStartDelay(6000).setDuration(800).start()
        hover()
    }

    private fun obtain(action: Int, source: Int, down: Long, x: Float = cx, y: Float = cy): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = if (source == InputDevice.SOURCE_MOUSE) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, source, 0)
    }

    private fun hover() {
        val e = obtain(MotionEvent.ACTION_HOVER_MOVE, InputDevice.SOURCE_MOUSE, SystemClock.uptimeMillis())
        web.dispatchGenericMotionEvent(e); e.recycle()
    }

    private fun touch(action: Int) {
        if (action == MotionEvent.ACTION_DOWN) touchDown = SystemClock.uptimeMillis()
        val e = obtain(action, InputDevice.SOURCE_TOUCHSCREEN, touchDown)
        web.dispatchTouchEvent(e); e.recycle()
    }

    /** Przewinięcie kółkiem myszy w miejscu kursora (dir: 1 = w dół). */
    private fun wheel(dir: Float) {
        val e = obtain(MotionEvent.ACTION_SCROLL, InputDevice.SOURCE_MOUSE, SystemClock.uptimeMillis())
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = cx; y = cy; setAxisValue(MotionEvent.AXIS_VSCROLL, -dir)
        })
        e.recycle()
        val s = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_SCROLL, 1,
            props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        web.dispatchGenericMotionEvent(s); s.recycle()
    }

    private fun move(dx: Int, dy: Int, repeat: Int) {
        val speed = dp(9f) * (1f + minOf(repeat, 14) * 0.45f) * app.config.cursorSpeed / 100f
        val w = (web.width - dp(4f)).toFloat()
        val h = (web.height - dp(4f)).toFloat()
        val nx = cx + dx * speed
        val ny = cy + dy * speed
        // przy górnej/dolnej krawędzi przewijaj stronę
        if (ny < 0 && dy < 0) wheel(-1.5f)
        if (ny > h && dy > 0) wheel(1.5f)
        cx = nx.coerceIn(0f, w)
        cy = ny.coerceIn(0f, h)
        placeCursor()
    }

    // ---------------- karty (widoki) ----------------

    private fun goView(i: Int) {
        if (views.isEmpty()) { showBanner("Brak listy kart tego dashboardu", 2500); return }
        viewIndex = ((i % views.size) + views.size) % views.size
        val (vp, title) = views[viewIndex]
        val target = "/$path/$vp"
        // nawigacja wewnątrz aplikacji HA (bez przeładowania strony)
        web.evaluateJavascript(
            "history.pushState(null,'','$target');window.dispatchEvent(new CustomEvent('location-changed',{detail:{replace:false}}));",
            null)
        showBanner("${viewIndex + 1}/${views.size}  $title", 2500)
    }

    private fun showBanner(text: String, ms: Long) {
        banner.text = text
        banner.visibility = View.VISIBLE
        handler.removeCallbacksAndMessages("banner")
        handler.postAtTime({ banner.visibility = View.GONE }, "banner", SystemClock.uptimeMillis() + ms)
    }

    private fun keyboardOpen(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.isAcceptingText && imm.isActive(web) && web.hasFocus() && touchDown > 0 &&
            SystemClock.uptimeMillis() - touchDown < 120_000 && imm.isActive
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // gdy wpisujesz tekst w polu – klawisze idą do strony
        if (keyboardOpen() && e.keyCode !in setOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
            return super.dispatchKeyEvent(e)
        }
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) move(-1, 0, e.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) move(1, 0, e.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (down) move(0, -1, e.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) move(0, 1, e.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down && e.repeatCount == 0) { touch(MotionEvent.ACTION_DOWN); cursor.pressed(true) }
                if (!down) { touch(MotionEvent.ACTION_UP); cursor.pressed(false) }
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { if (down) goView(viewIndex - 1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { if (down) goView(viewIndex + 1); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) repeat(4) { wheel(1.5f) }; return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) repeat(4) { wheel(-1.5f) }; return true }
            in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> { if (down) goView(e.keyCode - KeyEvent.KEYCODE_1); return true }
            KeyEvent.KEYCODE_BACK -> {
                if (!down) {
                    if (web.canGoBack()) web.goBack() else finish()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    /** Strzałka kursora. */
    private class CursorView(ctx: Context) : View(ctx) {
        private var isPressed = false
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f }
        private val pressFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4DD8FF"); style = Paint.Style.FILL }

        fun pressed(p: Boolean) { isPressed = p; invalidate() }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val p = Path().apply {
                moveTo(2f, 2f); lineTo(2f, h * 0.82f); lineTo(w * 0.28f, h * 0.62f); lineTo(w * 0.48f, h - 2f)
                lineTo(w * 0.62f, h * 0.92f); lineTo(w * 0.44f, h * 0.56f); lineTo(w * 0.78f, h * 0.56f); close()
            }
            c.drawPath(p, if (isPressed) pressFill else fill)
            c.drawPath(p, stroke)
        }
    }
}
