package pl.kamil.hatv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

/**
 * Ekran główny: kafelki encji sterowane pilotem + zakładki z dashboardami.
 *  OK            – główna akcja (włącz/wyłącz, otwórz/zamknij…)
 *  długie OK/MENU – szczegóły: jasność, temperatura, pozycja, głośność…
 */
class MainActivity : Activity() {

    private val app get() = App.instance
    private val ha get() = app.ha
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var tabs: LinearLayout
    private lateinit var grid: LinearLayout
    private lateinit var gridScroll: ScrollView
    private lateinit var detail: FrameLayout

    /** entity_id -> kafelek (do odświeżania jednego kafelka). */
    private val tiles = HashMap<String, Tile>()
    private var dashboards: List<Pair<String, String>> = emptyList()
    private var openedStart = false
    private var detailFor: String? = null
    private var detailRefresh: (() -> Unit)? = null
    private var lastFocusedId: String? = null

    private class Tile(val box: LinearLayout, val icon: TextView, val name: TextView, val state: TextView)

    private val stateListener: (String) -> Unit = { id ->
        if (id == "*") buildGrid() else {
            tiles[id]?.let { fillTile(id, it) }
            if (id == detailFor) detailRefresh?.invoke()
        }
    }
    private val statusListener: () -> Unit = {
        refreshStatus()
        if (ha.connected && dashboards.isEmpty()) loadDashboards()
    }
    private val configListener: () -> Unit = { handler.post { buildGrid(); loadDashboards() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger(this)
        app.startServer()

        root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0B1722")) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40f), dp(24f), dp(40f), dp(16f))
        }
        root.addView(col)

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            typeface = Mdi.typeface; text = Mdi.glyph("mdi:home-assistant")
            setTextColor(Color.parseColor("#18BCF2")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            setPadding(0, 0, dp(12f), 0)
        })
        top.addView(TextView(this).apply {
            text = "HA TV"; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        })
        statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#9FC3DB")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(24f), 0, 0, 0); maxLines = 2
        }
        top.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(top)

        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12f), 0, dp(12f)) }
        col.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabs) })

        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(40f)) }
        gridScroll = ScrollView(this).apply { addView(grid); isVerticalScrollBarEnabled = false }
        col.addView(gridScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        detail = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#C0000000"))
            isClickable = true
        }
        root.addView(detail, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
        buildTabs()
        buildGrid()
    }

    override fun onStart() {
        super.onStart()
        ha.onState(stateListener)
        ha.onStatus(statusListener)
        app.config.addListener(configListener)
        if (!ha.connected) ha.connect()
        refreshStatus()
        buildGrid()
        loadDashboards()
    }

    override fun onStop() {
        ha.offState(stateListener)
        ha.offStatus(statusListener)
        app.config.removeListener(configListener)
        super.onStop()
    }

    private fun refreshStatus() {
        val ip = localIpAddresses().firstOrNull() ?: "IP_TELEWIZORA"
        statusText.text = ha.status + "\nUstawienia: http://$ip:$API_PORT"
    }

    // ---------------- zakładki ----------------

    private fun loadDashboards() {
        val chosen = app.config.dashboards
        if (chosen.isNotEmpty()) {
            dashboards = chosen; buildTabs(); maybeOpenStart(); return
        }
        if (!ha.connected) return
        ha.listDashboards { list -> dashboards = list; buildTabs(); maybeOpenStart() }
    }

    private fun maybeOpenStart() {
        if (openedStart) return
        openedStart = true
        val s = app.config.start
        if (s != "tiles" && dashboards.any { it.first == s }) openDashboard(s, dashboards.first { it.first == s }.second)
    }

    private fun buildTabs() {
        tabs.removeAllViews()
        tabs.addView(tabButton("mdi:view-grid", "Encje", true) { gridScroll.post { firstTile()?.requestFocus() } })
        for ((path, title) in dashboards) {
            tabs.addView(tabButton("mdi:view-dashboard", title, false) { openDashboard(path, title) })
        }
        tabs.addView(tabButton("mdi:refresh", "Odśwież", false) {
            ha.connect(); Toast.makeText(this, "Łączenie z Home Assistantem…", Toast.LENGTH_SHORT).show()
        })
    }

    private fun openDashboard(path: String, title: String) {
        startActivity(Intent(this, DashboardActivity::class.java).putExtra("path", path).putExtra("title", title))
    }

    private fun tabButton(icon: String, label: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = Mdi.glyph(icon) + "  " + label
        typeface = Typeface.DEFAULT_BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
        isFocusable = true; isClickable = true
        isSelected = selected
        setTextColor(textColors())
        background = focusBg("#24435C", "#1E4E70")
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = dp(10f) }
        // ikona w czcionce MDI, tekst zwykłą czcionką
        val sp = android.text.SpannableString(text)
        sp.setSpan(MdiSpan(), 0, Mdi.glyph(icon).length, 0)
        text = sp
    }

    // ---------------- kafelki ----------------

    /** Lista pozycji: ulubione z ustawień albo automatycznie według domen. */
    private fun items(): List<String> {
        val fav = app.config.favorites
        if (fav.isNotEmpty()) return fav
        val out = mutableListOf<String>()
        for (d in Entities.DEFAULT_DOMAINS) {
            val ids = ha.states.keys.filter { Entities.domain(it) == d }
                .sortedBy { Entities.name(ha.states[it], it).lowercase() }
            if (ids.isEmpty()) continue
            out.add("#" + (Entities.DOMAIN_NAMES[d] ?: d))
            out.addAll(ids)
        }
        return out
    }

    private fun buildGrid() {
        val focusedBefore = lastFocusedId
        grid.removeAllViews()
        tiles.clear()
        val list = items()
        if (list.isEmpty()) {
            grid.addView(TextView(this).apply {
                val ip = localIpAddresses().firstOrNull() ?: "IP_TELEWIZORA"
                text = if (!ha.connected) "Brak połączenia z Home Assistantem.\n\nNa telefonie lub komputerze otwórz http://$ip:$API_PORT,\nwpisz adres Home Assistanta i token."
                else "Brak encji do pokazania.\nWybierz je na stronie http://$ip:$API_PORT"
                setTextColor(Color.parseColor("#C8E6FA")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setPadding(0, dp(40f), 0, 0); setLineSpacing(0f, 1.3f)
            })
            return
        }
        val cols = app.config.columns
        var row: LinearLayout? = null
        var inRow = 0
        for (item in list) {
            if (item.startsWith("#")) {
                row = null
                grid.addView(TextView(this).apply {
                    text = item.removePrefix("#").trim()
                    setTextColor(Color.parseColor("#FFD54F")); typeface = Typeface.DEFAULT_BOLD
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                    setPadding(dp(6f), dp(18f), 0, dp(6f))
                })
                continue
            }
            if (row == null || inRow >= cols) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row)
                inRow = 0
            }
            val t = makeTile(item)
            row.addView(t.box, LinearLayout.LayoutParams(0, dp(app.config.tileSize.toFloat()), 1f).apply {
                setMargins(dp(6f), dp(6f), dp(6f), dp(6f))
            })
            tiles[item] = t
            fillTile(item, t)
            inRow++
        }
        // wyrównaj ostatni rząd pustymi miejscami
        if (row != null) while (inRow < cols) {
            row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(dp(6f), 0, dp(6f), 0) })
            inRow++
        }
        val target = focusedBefore?.let { tiles[it]?.box } ?: firstTile()
        if (detail.visibility != View.VISIBLE) target?.post { target.requestFocus() }
    }

    private fun firstTile(): View? = tiles.values.firstOrNull()?.box?.let { first ->
        // pierwszy w kolejności wyświetlania
        items().firstOrNull { !it.startsWith("#") }?.let { tiles[it]?.box } ?: first
    }

    private fun makeTile(id: String): Tile {
        val size = app.config.tileSize / 100f
        val icon = TextView(this).apply {
            typeface = Mdi.typeface; setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f * size); includeFontPadding = false
        }
        val name = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * size); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
        }
        val state = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * size); typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            isFocusable = true; isClickable = true; isLongClickable = true
            addView(icon)
            addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(state)
            setOnClickListener { primary(id) }
            setOnLongClickListener { openDetail(id); true }
            setOnKeyListener { _, code, ev ->
                if (ev.action == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_INFO ||
                        code == KeyEvent.KEYCODE_PROG_YELLOW)) { openDetail(id); true } else false
            }
            setOnFocusChangeListener { v, has ->
                if (has) lastFocusedId = id
                v.animate().scaleX(if (has) 1.05f else 1f).scaleY(if (has) 1.05f else 1f).setDuration(120).start()
            }
        }
        return Tile(box, icon, name, state)
    }

    private fun fillTile(id: String, t: Tile) {
        val s = ha.states[id]
        val active = Entities.isActive(s)
        val off = Entities.isUnavailable(s)
        t.icon.text = Mdi.glyph(Entities.icon(id, s))
        t.icon.setTextColor(if (active) Color.parseColor("#FFC107") else if (off) Color.parseColor("#607D8B") else Color.parseColor("#9FC3DB"))
        t.name.text = Entities.name(s, id)
        t.state.text = Entities.stateText(id, s)
        t.state.setTextColor(if (active) Color.parseColor("#FFD54F") else Color.parseColor("#C8E6FA"))
        t.box.background = focusBg(if (active) "#3A3520" else "#1A3247", if (active) "#4A4326" else "#22405A",
            if (active) "#8A6D12" else "#1E6FA8")
    }

    private fun primary(id: String) {
        val s = ha.states[id]
        val act = Entities.primaryAction(id, s)
        if (act == null) { openDetail(id); return }
        ha.callService(act.first, act.second, id) { ok, msg ->
            if (!ok) Toast.makeText(this, "Nie udało się: $msg", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- okno szczegółów ----------------

    private fun openDetail(id: String) {
        detailFor = id
        detail.removeAllViews()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36f), dp(28f), dp(36f), dp(28f))
            background = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(Color.parseColor("#0F2A3F")) }
        }
        val scroll = ScrollView(this).apply { addView(panel) }
        detail.addView(scroll, FrameLayout.LayoutParams(dp(640f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        detail.visibility = View.VISIBLE

        val header = TextView(this).apply {
            setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        }
        val stateLine = TextView(this).apply {
            setTextColor(Color.parseColor("#FFD54F")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); setPadding(0, dp(4f), 0, dp(16f))
        }
        panel.addView(header); panel.addView(stateLine)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(rows)
        panel.addView(TextView(this).apply {
            text = "◀ ▶ zmiana wartości · OK wybór · WSTECZ zamknij"
            setTextColor(Color.parseColor("#8FB0C8")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(0, dp(14f), 0, 0)
        })

        val refreshers = mutableListOf<() -> Unit>()
        detailRefresh = {
            val s = ha.states[id]
            header.text = Entities.name(s, id)
            stateLine.text = Entities.stateText(id, s)
            refreshers.forEach { it() }
        }
        Controls(this, ha, id, rows, refreshers).build()
        detailRefresh?.invoke()
        rows.getChildAt(0)?.requestFocus()
    }

    private fun closeDetail() {
        detail.visibility = View.GONE
        detail.removeAllViews()
        val back = detailFor
        detailFor = null
        detailRefresh = null
        back?.let { tiles[it]?.box?.requestFocus() }
    }

    override fun onBackPressed() {
        if (detail.visibility == View.VISIBLE) { closeDetail(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // P+ / P− przewijają listę kafelków o ekran
        if (event.action == KeyEvent.ACTION_DOWN && detail.visibility != View.VISIBLE) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { gridScroll.pageScroll(View.FOCUS_DOWN); return true }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { gridScroll.pageScroll(View.FOCUS_UP); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------- wygląd ----------------

    fun textColors() = android.content.res.ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_selected), intArrayOf()),
        intArrayOf(Color.parseColor("#04212E"), Color.parseColor("#FFD54F"), Color.WHITE)
    )

    fun focusBg(normal: String, selected: String, focused: String = "#4DD8FF") = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
            cornerRadius = dp(14f).toFloat(); setColor(Color.parseColor(focused)); setStroke(dp(3f), Color.WHITE)
        })
        addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply {
            cornerRadius = dp(14f).toFloat(); setColor(Color.parseColor(selected)); setStroke(dp(2f), Color.parseColor("#FFD54F"))
        })
        addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(14f).toFloat(); setColor(Color.parseColor(normal)) })
    }
}

/** Fragment tekstu wyświetlany czcionką z ikonami MDI. */
class MdiSpan : android.text.style.MetricAffectingSpan() {
    override fun updateDrawState(tp: android.text.TextPaint) { Mdi.typeface?.let { tp.typeface = it } }
    override fun updateMeasureState(tp: android.text.TextPaint) { Mdi.typeface?.let { tp.typeface = it } }
}
