/*
 * Nawigacja pilotem po dashboardzie Home Assistanta (bez kursora).
 * Strzałki przenoszą podświetlenie na najbliższy element w danym kierunku
 * (karty, przełączniki, przyciski, zakładki), OK klika podświetlony element.
 * Wstrzykiwane przez aplikację HA TV.
 */
(function () {
  if (window.haNav) return;

  var TAGS = {
    "button": 1, "a": 1, "input": 1, "select": 1, "textarea": 1,
    "ha-switch": 1, "ha-icon-button": 1, "ha-button": 1, "mwc-button": 1, "ha-entity-toggle": 1,
    "ha-checkbox": 1, "ha-radio": 1, "ha-slider": 1, "ha-control-slider": 1, "ha-control-button": 1,
    "ha-control-switch": 1, "ha-control-select": 1, "ha-tile-icon": 1, "ha-tab": 1, "paper-tab": 1, "sl-tab": 1,
    "ha-tab-group-tab": 1, "ha-md-list-item": 1, "ha-list-item": 1, "mwc-list-item": 1, "paper-icon-item": 1,
    "ha-outlined-icon-button": 1, "ha-menu-button": 1, "ha-icon-button-arrow-prev": 1, "ha-big-number": 0,
    "ha-state-control-climate-temperature": 0, "ha-select": 1, "ha-textfield": 1, "ha-md-button-menu": 1
  };
  var ROLES = { "button": 1, "tab": 1, "switch": 1, "checkbox": 1, "slider": 1, "option": 1, "menuitem": 1, "link": 1, "radio": 1 };
  var DIALOGS = { "ha-dialog": 1, "ha-md-dialog": 1, "ha-adaptive-dialog": 1, "mwc-dialog": 1, "ha-wa-dialog": 1, "ha-more-info-dialog": 0 };

  var cur = null;
  var box = document.createElement("div");
  box.id = "hatv-focus";
  box.style.cssText = "position:fixed;z-index:2147483647;pointer-events:none;border:4px solid #18bcf2;" +
    "border-radius:14px;box-shadow:0 0 0 3px rgba(255,255,255,.85),0 0 24px rgba(24,188,242,.9);" +
    "transition:all .12s ease-out;display:none;";
  (document.body || document.documentElement).appendChild(box);

  function visible(el, r) {
    if (r.width < 6 || r.height < 6) return false;
    if (el.hasAttribute && (el.hasAttribute("disabled") || el.getAttribute("aria-hidden") === "true")) return false;
    var s = getComputedStyle(el);
    if (s.visibility === "hidden" || s.display === "none" || s.pointerEvents === "none" || Number(s.opacity) === 0) return false;
    return true;
  }

  function isDialogOpen(el) {
    var t = el.tagName.toLowerCase();
    if (!DIALOGS.hasOwnProperty(t)) return false;
    return el.hasAttribute("open") || el.open === true;
  }

  // zbiera kandydatów z całego dokumentu (także z shadow DOM); otwarte okno dialogowe zawęża wybór
  function collect() {
    var found = [], dialogs = [], cards = [];
    function walk(root) {
      var all = root.querySelectorAll("*");
      for (var i = 0; i < all.length; i++) {
        var el = all[i];
        if (el.id === "hatv-focus") continue;
        var t = el.tagName.toLowerCase();
        if (isDialogOpen(el)) dialogs.push(el);
        var role = el.getAttribute && el.getAttribute("role");
        var ok = TAGS[t] === 1 || (role && ROLES[role]) ||
          (el.hasAttribute && el.hasAttribute("tabindex") && el.tabIndex >= 0 && t !== "div" || false) ||
          (el.classList && (el.classList.contains("clickable") || el.classList.contains("pointer")));
        if (t === "ha-card") cards.push(el);
        else if (ok) found.push(el);
        if (el.shadowRoot) walk(el.shadowRoot);
      }
    }
    walk(document);

    var vw = innerWidth, vh = innerHeight;
    function rectOf(el) { return el.getBoundingClientRect(); }
    var list = [];
    var byKey = {};
    found.forEach(function (el) {
      var r = rectOf(el);
      if (!visible(el, r)) return;
      if (r.width * r.height > vw * vh * 0.6) return;
      var k = Math.round(r.left / 3) + ":" + Math.round(r.top / 3) + ":" + Math.round(r.width / 3) + ":" + Math.round(r.height / 3);
      byKey[k] = { el: el, r: r }; // głębszy element z tym samym prostokątem wygrywa
    });
    Object.keys(byKey).forEach(function (k) { list.push(byKey[k]); });

    // karta bez przycisków w środku (np. wskaźnik, wykres) też może być celem
    cards.forEach(function (el) {
      var r = rectOf(el);
      if (!visible(el, r)) return;
      var inner = list.some(function (c) {
        return c.r.left >= r.left - 1 && c.r.right <= r.right + 1 && c.r.top >= r.top - 1 && c.r.bottom <= r.bottom + 1;
      });
      if (!inner) list.push({ el: el, r: r });
    });

    // otwarte okno (np. szczegóły encji) – tylko jego elementy
    if (dialogs.length) {
      var d = dialogs[dialogs.length - 1];
      var dr = d.getBoundingClientRect();
      var inside = list.filter(function (c) {
        return c.r.left >= dr.left - 2 && c.r.right <= dr.right + 2 && c.r.top >= dr.top - 2 && c.r.bottom <= dr.bottom + 2 &&
          c.el !== d;
      });
      if (inside.length) list = inside;
    }
    return list;
  }

  function center(r) { return { x: r.left + r.width / 2, y: r.top + r.height / 2 }; }

  function draw() {
    if (!cur || !cur.isConnected) { box.style.display = "none"; return; }
    var r = cur.getBoundingClientRect();
    box.style.display = "block";
    box.style.left = (r.left - 6) + "px";
    box.style.top = (r.top - 6) + "px";
    box.style.width = (r.width + 4) + "px";
    box.style.height = (r.height + 4) + "px";
  }

  function focusEl(el) {
    cur = el;
    try { el.scrollIntoView({ block: "nearest", inline: "nearest", behavior: "smooth" }); } catch (e) {}
    draw();
    setTimeout(draw, 200); setTimeout(draw, 450);
  }

  function initial(list) {
    // pierwszy element treści (z pominięciem paska bocznego i górnego)
    var best = null, bestScore = 1e9;
    list.forEach(function (c) {
      if (c.r.bottom < 0 || c.r.top > innerHeight) return;
      var penalty = (c.r.left < 90 ? 5000 : 0) + (c.r.top < 70 ? 3000 : 0);
      var s = c.r.top * 2 + c.r.left + penalty;
      if (s < bestScore) { bestScore = s; best = c.el; }
    });
    return best || (list[0] && list[0].el);
  }

  function isSlider(el) {
    if (!el) return false;
    var t = el.tagName.toLowerCase();
    return t.indexOf("slider") >= 0 || (el.getAttribute && el.getAttribute("role") === "slider");
  }

  function sendKey(el, key, code) {
    try { el.focus(); } catch (e) {}
    ["keydown", "keyup"].forEach(function (type) {
      el.dispatchEvent(new KeyboardEvent(type, { key: key, code: key, keyCode: code, which: code, bubbles: true, composed: true }));
    });
  }

  function move(dir) {
    var list = collect();
    if (!list.length) { cur = null; draw(); return "none"; }
    var inList = cur && list.some(function (c) { return c.el === cur; });
    if (!inList) { focusEl(initial(list)); return "init"; }

    if (isSlider(cur) && (dir === "left" || dir === "right")) {
      sendKey(cur, dir === "left" ? "ArrowLeft" : "ArrowRight", dir === "left" ? 37 : 39);
      draw();
      return "slider";
    }

    var cr = cur.getBoundingClientRect(), cc = center(cr);
    var best = null, bestScore = 1e12;
    list.forEach(function (c) {
      if (c.el === cur) return;
      var r = c.r, p = center(r), primary, secondary;
      switch (dir) {
        case "right": if (p.x <= cc.x + 2 || r.left < cr.left + 4) return; primary = Math.max(0, r.left - cr.right); secondary = Math.abs(p.y - cc.y); break;
        case "left": if (p.x >= cc.x - 2 || r.right > cr.right - 4) return; primary = Math.max(0, cr.left - r.right); secondary = Math.abs(p.y - cc.y); break;
        case "down": if (p.y <= cc.y + 2 || r.top < cr.top + 4) return; primary = Math.max(0, r.top - cr.bottom); secondary = Math.abs(p.x - cc.x); break;
        case "up": if (p.y >= cc.y - 2 || r.bottom > cr.bottom - 4) return; primary = Math.max(0, cr.top - r.bottom); secondary = Math.abs(p.x - cc.x); break;
      }
      var s = primary + secondary * 2.5;
      if (s < bestScore) { bestScore = s; best = c.el; }
    });
    if (best) { focusEl(best); return "moved"; }
    // brak elementu w tym kierunku – przewiń stronę
    if (dir === "down" || dir === "up") {
      try { window.scrollBy(0, (dir === "down" ? 1 : -1) * innerHeight * 0.6); } catch (e) {}
      setTimeout(draw, 300);
    }
    return "edge";
  }

  // środek podświetlonego elementu (w px strony) – aplikacja klika w to miejsce
  function target() {
    var list = collect();
    if (!cur || !cur.isConnected || !list.some(function (c) { return c.el === cur; })) {
      var el = initial(list);
      if (el) focusEl(el);
      return JSON.stringify({ ok: false });
    }
    var r = cur.getBoundingClientRect(), c = center(r);
    return JSON.stringify({ ok: true, x: c.x, y: c.y, iw: innerWidth, ih: innerHeight });
  }

  // odśwież ramkę przy przewijaniu i zmianach strony
  window.addEventListener("scroll", draw, true);
  setInterval(function () {
    if (cur && !cur.isConnected) { cur = null; var l = collect(); if (l.length) focusEl(initial(l)); }
    draw();
  }, 700);

  window.haNav = { move: move, target: target, redraw: draw };
  setTimeout(function () { var l = collect(); if (l.length) focusEl(initial(l)); }, 1500);
})();
