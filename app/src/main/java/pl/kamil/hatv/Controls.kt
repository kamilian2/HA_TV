package pl.kamil.hatv

import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Wiersze okna szczegółów encji – zależnie od domeny:
 * przyciski akcji, suwaki (◀ ▶) i listy wyboru.
 */
class Controls(
    private val act: MainActivity,
    private val ha: HaClient,
    private val id: String,
    private val rows: LinearLayout,
    private val refreshers: MutableList<() -> Unit>,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val domain = Entities.domain(id)
    private fun st(): JSONObject? = ha.states[id]
    private fun attrs(): JSONObject = st()?.optJSONObject("attributes") ?: JSONObject()
    private fun state(): String = st()?.optString("state") ?: ""

    private fun call(service: String, data: JSONObject? = null, svcDomain: String = domain) {
        ha.callService(svcDomain, service, id, data) { ok, msg ->
            if (!ok) Toast.makeText(act, "Nie udało się: $msg", Toast.LENGTH_LONG).show()
        }
    }

    fun build() {
        val a = attrs()
        when (domain) {
            "light" -> {
                action("mdi:power", "Włącz / wyłącz") { call("toggle") }
                slider("Jasność", "%", 0.0, 100.0, 10.0,
                    { if (state() == "on") attrs().optInt("brightness", 0) / 2.55 else 0.0 },
                    { v -> if (v <= 0) call("turn_off") else call("turn_on", JSONObject().put("brightness_pct", v.toInt())) })
                if (a.has("min_color_temp_kelvin")) {
                    slider("Barwa światła", " K", a.optDouble("min_color_temp_kelvin", 2000.0), a.optDouble("max_color_temp_kelvin", 6500.0), 250.0,
                        { attrs().optDouble("color_temp_kelvin", Double.NaN) },
                        { v -> call("turn_on", JSONObject().put("color_temp_kelvin", v.toInt())) })
                }
                listFrom(a.optJSONArray("effect_list"))?.let { effects ->
                    choice("Efekt", { attrs().optString("effect", "") }, effects) { v -> call("turn_on", JSONObject().put("effect", v)) }
                }
            }
            "switch", "input_boolean", "fan", "automation", "siren", "humidifier", "remote" -> {
                action("mdi:power-on", "Włącz") { call("turn_on") }
                action("mdi:power-off", "Wyłącz") { call("turn_off") }
                if (domain == "fan" && a.has("percentage")) {
                    slider("Prędkość", "%", 0.0, 100.0, a.optDouble("percentage_step", 10.0).coerceAtLeast(1.0),
                        { attrs().optDouble("percentage", 0.0) },
                        { v -> call("set_percentage", JSONObject().put("percentage", v.toInt())) })
                }
                if (domain == "automation") action("mdi:play", "Uruchom teraz") { call("trigger") }
            }
            "cover" -> {
                action("mdi:arrow-up-bold", "Otwórz") { call("open_cover") }
                action("mdi:stop", "Stop") { call("stop_cover") }
                action("mdi:arrow-down-bold", "Zamknij") { call("close_cover") }
                if (a.has("current_position")) {
                    slider("Pozycja", "%", 0.0, 100.0, 10.0, { attrs().optDouble("current_position", 0.0) },
                        { v -> call("set_cover_position", JSONObject().put("position", v.toInt())) })
                }
                if (a.has("current_tilt_position")) {
                    slider("Nachylenie", "%", 0.0, 100.0, 10.0, { attrs().optDouble("current_tilt_position", 0.0) },
                        { v -> call("set_cover_tilt_position", JSONObject().put("tilt_position", v.toInt())) })
                }
            }
            "climate" -> {
                val step = a.optDouble("target_temp_step", 0.5).takeIf { it > 0 } ?: 0.5
                slider("Temperatura zadana", "°", a.optDouble("min_temp", 5.0), a.optDouble("max_temp", 35.0), step,
                    { attrs().optDouble("temperature", Double.NaN) },
                    { v -> call("set_temperature", JSONObject().put("temperature", v)) })
                listFrom(a.optJSONArray("hvac_modes"))?.let { modes ->
                    choice("Tryb", { state() }, modes, { Entities.hvacName(it) }) { v -> call("set_hvac_mode", JSONObject().put("hvac_mode", v)) }
                }
                listFrom(a.optJSONArray("preset_modes"))?.let { p ->
                    choice("Preset", { attrs().optString("preset_mode", "") }, p) { v -> call("set_preset_mode", JSONObject().put("preset_mode", v)) }
                }
                listFrom(a.optJSONArray("fan_modes"))?.let { f ->
                    choice("Wentylator", { attrs().optString("fan_mode", "") }, f) { v -> call("set_fan_mode", JSONObject().put("fan_mode", v)) }
                }
                info("Temperatura w pomieszczeniu") { attrs().opt("current_temperature")?.let { "$it°" } ?: "—" }
            }
            "media_player" -> {
                action("mdi:play-pause", "Odtwórz / pauza") { call("media_play_pause") }
                action("mdi:skip-previous", "Poprzedni") { call("media_previous_track") }
                action("mdi:skip-next", "Następny") { call("media_next_track") }
                slider("Głośność", "%", 0.0, 100.0, 5.0, { attrs().optDouble("volume_level", 0.0) * 100 },
                    { v -> call("volume_set", JSONObject().put("volume_level", v / 100.0)) })
                action("mdi:volume-off", "Wycisz / przywróć") {
                    call("volume_mute", JSONObject().put("is_volume_muted", !attrs().optBoolean("is_volume_muted", false)))
                }
                listFrom(a.optJSONArray("source_list"))?.let { src ->
                    choice("Źródło", { attrs().optString("source", "") }, src) { v -> call("select_source", JSONObject().put("source", v)) }
                }
                action("mdi:power", "Włącz / wyłącz") { call(if (state() == "off") "turn_on" else "turn_off") }
            }
            "lock" -> {
                action("mdi:lock", "Zamknij") { call("lock") }
                action("mdi:lock-open-variant", "Otwórz") { call("unlock") }
            }
            "scene", "script" -> action("mdi:play", "Uruchom") { call("turn_on") }
            "button", "input_button" -> action("mdi:gesture-tap-button", "Naciśnij") { call("press") }
            "vacuum" -> {
                action("mdi:play", "Start") { call("start") }
                action("mdi:pause", "Pauza") { call("pause") }
                action("mdi:home-import-outline", "Do stacji") { call("return_to_base") }
            }
            "alarm_control_panel" -> {
                action("mdi:shield-off", "Rozbrój") { call("alarm_disarm") }
                action("mdi:shield-home", "Uzbrój (w domu)") { call("alarm_arm_home") }
                action("mdi:shield-lock", "Uzbrój (poza domem)") { call("alarm_arm_away") }
            }
            "number", "input_number" -> {
                slider("Wartość", " " + a.optString("unit_of_measurement", ""), a.optDouble("min", 0.0), a.optDouble("max", 100.0),
                    a.optDouble("step", 1.0).takeIf { it > 0 } ?: 1.0,
                    { st()?.optString("state")?.toDoubleOrNull() ?: Double.NaN },
                    { v -> call("set_value", JSONObject().put("value", v)) })
            }
            "select", "input_select" -> {
                listFrom(a.optJSONArray("options"))?.let { o ->
                    choice("Wybór", { state() }, o) { v -> call("select_option", JSONObject().put("option", v)) }
                }
            }
        }
        // kilka atrybutów do podglądu
        val skip = setOf("friendly_name", "icon", "entity_picture", "supported_features", "supported_color_modes",
            "effect_list", "hvac_modes", "preset_modes", "fan_modes", "source_list", "options", "attribution")
        var n = 0
        for (k in a.keys()) {
            if (k in skip || n >= 8) continue
            val v = a.opt(k)
            if (v is JSONArray || v is JSONObject) continue
            info(k) { attrs().opt(k)?.toString() ?: "" }
            n++
        }
    }

    private fun listFrom(a: JSONArray?): List<String>? =
        a?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() } }?.takeIf { it.isNotEmpty() }

    // ---------------- wiersze ----------------

    private fun baseRow(focusable: Boolean): Pair<LinearLayout, Pair<TextView, TextView>> {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dp(18f), act.dp(12f), act.dp(18f), act.dp(12f))
            isFocusable = focusable; isClickable = focusable
            if (focusable) background = act.focusBg("#1A3247", "#22405A")
        }
        val label = TextView(act).apply { setTextColor(act.textColors()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f) }
        val value = TextView(act).apply {
            setTextColor(act.textColors()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(value)
        rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = act.dp(8f) })
        return row to (label to value)
    }

    private fun action(icon: String, label: String, fn: () -> Unit) {
        val (row, tv) = baseRow(true)
        val text = Mdi.glyph(icon) + "   " + label
        tv.first.text = android.text.SpannableString(text).apply { setSpan(MdiSpan(), 0, Mdi.glyph(icon).length, 0) }
        row.setOnClickListener { fn() }
    }

    private fun fmt(v: Double): String =
        if (v.isNaN()) "—" else if (v == Math.floor(v)) v.toLong().toString() else String.format(Locale("pl", "PL"), "%.1f", v)

    private fun slider(label: String, unit: String, min: Double, max: Double, step: Double,
                       get: () -> Double, set: (Double) -> Unit) {
        val (row, tv) = baseRow(true)
        tv.first.text = label
        var pending: Double? = null
        val send = Runnable { pending?.let { set(it) } }
        fun show() { tv.second.text = "◀  " + fmt(pending ?: get()) + unit.trimEnd() + "  ▶" }
        refreshers.add { if (pending == null) show() }
        show()
        row.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val dir = when (code) { KeyEvent.KEYCODE_DPAD_RIGHT -> 1; KeyEvent.KEYCODE_DPAD_LEFT -> -1; else -> 0 }
            if (dir == 0) return@setOnKeyListener false
            val cur = pending ?: get().let { if (it.isNaN()) min else it }
            val v = (Math.round((cur + dir * step) / step) * step).coerceIn(min, max)
            pending = v
            show()
            handler.removeCallbacks(send)
            handler.postDelayed(send, 400)
            handler.postDelayed({ pending = null }, 3000)
            true
        }
    }

    private fun choice(label: String, get: () -> String, options: List<String>, nameOf: (String) -> String = { it },
                       set: (String) -> Unit) {
        val (row, tv) = baseRow(true)
        tv.first.text = label
        var pending: String? = null
        fun show() { tv.second.text = "◀  " + nameOf(pending ?: get()) + "  ▶" }
        refreshers.add { if (pending == null) show() }
        show()
        fun move(dir: Int) {
            val cur = pending ?: get()
            val i = options.indexOf(cur).let { if (it < 0) 0 else it }
            val n = ((i + dir) % options.size + options.size) % options.size
            pending = options[n]
            show()
            set(options[n])
            handler.postDelayed({ pending = null; show() }, 2500)
        }
        row.setOnKeyListener { _, code, ev ->
            if (ev.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (code) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { move(1); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { move(-1); true }
                else -> false
            }
        }
        row.setOnClickListener { move(1) }
    }

    private fun info(label: String, get: () -> String) {
        val (_, tv) = baseRow(false)
        tv.first.text = label
        tv.first.setTextColor(Color.parseColor("#9FC3DB"))
        tv.second.setTextColor(Color.parseColor("#C8E6FA"))
        tv.first.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        tv.second.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        refreshers.add { tv.second.text = get() }
        tv.second.text = get()
    }
}
