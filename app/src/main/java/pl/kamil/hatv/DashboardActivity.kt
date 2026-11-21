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
 *  strzałki      – przejście do sąsiedniej karty / przycisku (podświetlenie, bez kursora)
 *  ◀ ▶ na suwaku – zmiana wartości
 *  OK            – kliknięcie podświetlonego elementu (przytrzymanie = długie naciśnięcie)
 *  P+ / P−       – poprzednia / następna karta (widok)
 *  1–9           – karta o tym numerze
 *  ⏪ ⏩          – przewijanie strony
 *  WSTECZ        – zamknięcie okienka / powrót do kafelków
 */
class DashboardActivity : Activity() {

    private val app get() = App.instance
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var web: WebView
    private lateinit var banner: TextView
    private var path = "lovelace"
    private var views: List<Pair<String, String>> = emptyList()
    private var viewIndex = 0
    private var cx = 0f
    private var cy = 0f
    private var touchDown = 0L
    private var okHeld = false
    private var okDownSent = false
    private val navJs by lazy { assets.open("nav.js").bufferedReader(Charsets.UTF_8).use { it.readText() } }

    /** Szerokość strony w px (jak okno przeglądarki na PC). */
    private fun injectViewport() {
        val w = app.config.pageWidth
        web.evaluateJavascript(
            "(function(){var m=document.querySelector('meta[name=viewport]');" +
                "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}" +
                "m.setAttribute('content','width=$w');})();", null)
    }

    private fun injectNav() {
        web.evaluateJavascript(navJs, null)
    }

    /** Ruch podświetlenia (wstrzykuje skrypt, gdyby strona go zgubiła). */
    private fun nav(dir: String) {
        web.evaluateJavascript("(window.haNav?haNav.move('$dir'):'missing')") { r ->
            if (r?.contains("missing") == true) { injectViewport(); injectNav() }
        }
    }

    /** OK wciśnięte: dotknij środka podświetlonego elementu. */
    private fun okDown() {
        okHeld = true
        okDownSent = false
        web.evaluateJavascript("(window.haNav?haNav.target():'{}')") { r ->
            val j = try { JSONObject(org.json.JSONTokener(r).nextValue().toString()) } catch (e: Exception) { JSONObject() }
            if (!j.optBoolean("ok")) { if (j.length() == 0) injectNav(); return@evaluateJavascript }
            val scale = web.width / j.optDouble("iw", web.width.toDouble())
            cx = (j.optDouble("x") * scale).toFloat()
            cy = (j.optDouble("y") * scale).toFloat()
            touch(MotionEvent.ACTION_DOWN)
            okDownSent = true
            if (!okHeld) touch(MotionEvent.ACTION_UP)
        }
    }

    private fun okUp() {
        okHeld = false
        if (okDownSent) { touch(MotionEvent.ACTION_UP); okDownSent = false }
    }

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
            // strona układana na szerokość jak na PC (np. 1920 px), a potem dopasowana do ekranu TV
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.textZoom = 100
            isFocusable = true
            isFocusableInTouchMode = true
            addJavascriptInterface(ExternalApp(), "externalApp")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageCommitVisible(view: WebView, url: String) { injectViewport() }
                override fun onPageFinished(view: WebView, url: String) { injectViewport(); injectNav() }
                override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                    if (req.isForMainFrame) showBanner("Nie można otworzyć Home Assistanta: ${err.description}", 8000)
                }
            }
        }
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))


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
            showBanner((intent.getStringExtra("title") ?: "Dashboard") + " · strzałki – wybór · OK – kliknij · P+/P− – karty", 5000)
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

    private fun obtain(action: Int, source: Int, down: Long, x: Float = cx, y: Float = cy): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = if (source == InputDevice.SOURCE_MOUSE) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        return MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, source, 0)
    }

    private fun touch(action: Int) {
        if (action == MotionEvent.ACTION_DOWN) touchDown = SystemClock.uptimeMillis()
        val e = obtain(action, InputDevice.SOURCE_TOUCHSCREEN, touchDown)
        web.dispatchTouchEvent(e); e.recycle()
    }

    private fun scrollPage(dir: Int) {
        web.evaluateJavascript("window.scrollBy(0, ${dir} * innerHeight * 0.8); setTimeout(function(){window.haNav&&haNav.redraw()},300);", null)
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
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) nav("left"); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) nav("right"); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (down) nav("up"); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) nav("down"); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down && e.repeatCount == 0) okDown()
                if (!down) okUp()
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { if (down) goView(viewIndex - 1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { if (down) goView(viewIndex + 1); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) scrollPage(1); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) scrollPage(-1); return true }
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

}
