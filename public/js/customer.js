"use strict";

(function () {
  const STORAGE_KEY = "sg_customer_seat";
  const DISH_IMAGES = {
    "Paneer Tikka": "/public/dishes/paneer-tikka.jpg",
    "Chicken Wings": "/public/dishes/chicken-wings.jpg",
    "Veg Biryani": "/public/dishes/veg-biryani.jpg",
    "Butter Chicken": "/public/dishes/butter-chicken.jpg",
    "Gulab Jamun": "/public/dishes/gulab-jamun.jpg",
    "Masala Chai": "/public/dishes/masala-chai.jpg",
    "Crispy Aloo Tikki": "/public/dishes/crispy-aloo-tikki.jpg",
    "Hara Bhara Kebab": "/public/dishes/hara-bhara-kebab.jpg",
    "Chicken Tikka": "/public/dishes/chicken-tikka.jpg",
    "Mutton Seekh Kebab": "/public/dishes/mutton-seekh-kebab.jpg",
    "Dal Makhani": "/public/dishes/dal-makhani.jpg",
    "Palak Paneer": "/public/dishes/palak-paneer.jpg",
    "Chicken Biryani": "/public/dishes/chicken-biryani.jpg",
    "Rogan Josh": "/public/dishes/rogan-josh.jpg",
    "Rasmalai": "/public/dishes/rasmalai.jpg",
    "Mango Lassi": "/public/dishes/mango-lassi.jpg"
  };
  const DISH_FALLBACKS = {
    "Paneer Tikka": "/public/dishes/paneer-tikka.svg",
    "Chicken Wings": "/public/dishes/chicken-wings.svg",
    "Veg Biryani": "/public/dishes/veg-biryani.svg",
    "Butter Chicken": "/public/dishes/butter-chicken.svg",
    "Gulab Jamun": "/public/dishes/gulab-jamun.svg",
    "Masala Chai": "/public/dishes/masala-chai.svg"
  };
  const TASTING_NOTES = {
    "Paneer Tikka": "Charred cottage cheese marinated in hung yogurt, Kashmiri chili, and carom seeds.",
    "Chicken Wings": "Crisp tandoor-roasted wings glazed with roasted red pepper, garlic, and lime.",
    "Veg Biryani": "Long-grain basmati layered with seasonal vegetables, saffron milk, and fried shallots.",
    "Butter Chicken": "Char-grilled chicken simmered in velvety heirloom tomato, fenugreek, and butter gravy."
  };
  const SPACE_DESCRIPTIONS = {
    WINDOW_ALCOVE: "Sunlit street-side booths with intimate 2 and 4-seat timber tables.",
    GARDEN_COURTYARD: "Breezy lantern-lit courtyard seating surrounded by lush planters.",
    MAIN_HALL: "Heart of the dining room with warm acoustics and brass pendant glow.",
    CORNER_LOUNGE: "Generous 6-seat corner tables crafted for family feasts and group celebrations."
  };
  const FALLBACK_REVIEWS = [
    { rating: 5, category: "Food", customerName: "Ananya R.", tableNumber: 4, comment: "The Paneer Tikka came straight off the charcoal and our whole table shared the bill effortlessly from our phones." },
    { rating: 5, category: "Ambience", customerName: "Kabir M.", tableNumber: 6, comment: "Warm courtyard lighting, attentive kitchen updates, and the Dal Makhani was unforgettable." },
    { rating: 5, category: "Service", customerName: "Meera S.", tableNumber: 2, comment: "Loved scanning the table QR code so everyone at our table could add their own dishes to one shared bill." }
  ];
  const AVATAR_PALETTE = ["#153124", "#b07d2f", "#227647", "#8b241a", "#35536b", "#6b4263"];

  const state = {
    menu: [],
    tables: [],
    reviews: [],
    seat: loadSeat(),
    session: null,
    pageView: "home", // "home" | "tables"
    entryMode: "book", // "book" | "join"
    selectedTable: null,
    joinPrefill: "",
    category: "ALL",
    dietFilter: "ALL",
    searchQuery: "",
    billTab: "by-guest",
    picks: {},
    payMode: "UPI",
    receiptNote: "",
    feedbackSubmitted: false,
    feedbackRating: 5,
    feedbackCategory: "Overall",
    error: "",
    sse: null,
    sseChannel: null,
    durationTimer: null,
    lastViewMode: null,
    lastBillTotal: null
  };

  const app = document.getElementById("app");
  const livePill = document.getElementById("live-pill");
  const liveLabel = document.getElementById("live-label");
  const topbarNav = document.getElementById("topbar-nav");
  const navTablesBtn = document.getElementById("nav-tables-btn");
  const qrDialog = document.getElementById("qr-dialog");
  const checkoutDialog = document.getElementById("checkout-dialog");

  function loadSeat() {
    try {
      const raw = sessionStorage.getItem(STORAGE_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch {
      return null;
    }
  }

  function saveSeat(seat) {
    state.seat = seat;
    if (seat) sessionStorage.setItem(STORAGE_KEY, JSON.stringify(seat));
    else sessionStorage.removeItem(STORAGE_KEY);
  }

  function esc(text) {
    return String(text ?? "").replace(/[&<>"']/g, c => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
    })[c]);
  }

  function money(v) {
    return new Intl.NumberFormat("en-IN", {
      style: "currency",
      currency: "INR",
      minimumFractionDigits: 2
    }).format(Number(v || 0));
  }

  function formatDuration(seconds) {
    const s = Math.max(0, Math.floor(seconds || 0));
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const sec = s % 60;
    if (h > 0) return `${h}h ${String(m).padStart(2, "0")}m ${String(sec).padStart(2, "0")}s`;
    return `${m}m ${String(sec).padStart(2, "0")}s`;
  }

  function avatarColor(id) {
    return AVATAR_PALETTE[Math.abs(Number(id || 0)) % AVATAR_PALETTE.length];
  }

  function initials(name) {
    const parts = String(name || "?").trim().split(/\s+/);
    return (parts[0][0] || "?").toUpperCase();
  }

  function toast(msg, actionLabel, onAction) {
    const el = document.getElementById("toast");
    el.innerHTML = "";
    const span = document.createElement("span");
    span.textContent = msg;
    el.appendChild(span);
    if (actionLabel && typeof onAction === "function") {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.textContent = actionLabel;
      btn.style.cssText = "border:1px solid #e4be73;background:transparent;color:#e4be73;border-radius:999px;padding:2px 9px;font-size:11px;font-weight:700;";
      btn.addEventListener("click", () => {
        el.classList.remove("show");
        onAction();
      });
      el.appendChild(btn);
    }
    el.classList.add("show");
    clearTimeout(toast._t);
    toast._t = setTimeout(() => el.classList.remove("show"), 2600);
  }

  async function api(url, options) {
    const res = await fetch(url, options);
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || "Request failed");
    return data;
  }

  function postJson(url, body) {
    return api(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    });
  }

  function setLiveStatus(online, label) {
    livePill.classList.toggle("online", online);
    liveLabel.textContent = label;
  }

  function connectSse(channel) {
    if (state.sseChannel === channel && state.sse) return;
    if (state.sse) {
      state.sse.close();
      state.sse = null;
    }
    state.sseChannel = channel;
    const url = channel === "tables"
      ? "/api/events?channel=tables"
      : `/api/events?sessionId=${encodeURIComponent(channel.replace("session:", ""))}`;

    const es = new EventSource(url);
    state.sse = es;

    es.addEventListener("connected", () => setLiveStatus(true, "Live sync"));
    es.addEventListener("update", () => {
      setLiveStatus(true, "Live sync");
      if (state.seat) refreshCurrentSession(true);
      else refreshTables();
    });
    es.onerror = () => setLiveStatus(false, "Reconnecting");
  }

  async function refreshMenu() {
    state.menu = await api("/api/menu");
  }

  async function refreshTables() {
    state.tables = await api("/api/tables");
    if (state.selectedTable) {
      const stillAvail = state.tables.find(
        t => t.tableNumber === state.selectedTable && t.status === "AVAILABLE"
      );
      if (!stillAvail) state.selectedTable = null;
    }
    if (!state.seat) {
      if (state.pageView === "home" && document.getElementById("home-open-count")) {
        updateHomeCountsInPlace();
      } else {
        render();
      }
    }
  }

  function updateHomeCountsInPlace() {
    const availCount = state.tables.filter(t => t.status === "AVAILABLE").length;
    const totalCount = state.tables.length || 10;
    const countEl = document.getElementById("home-open-count");
    if (countEl) countEl.textContent = `${availCount} of ${totalCount} Open`;
    const spacesMap = new Map();
    for (const t of state.tables) {
      if (!spacesMap.has(t.zone)) spacesMap.set(t.zone, { open: 0, total: 0 });
      const entry = spacesMap.get(t.zone);
      entry.total++;
      if (t.status === "AVAILABLE") entry.open++;
    }
    for (const [zone, stats] of spacesMap.entries()) {
      const badge = document.querySelector(`[data-space-status="${zone}"]`);
      if (badge) badge.textContent = `${stats.open} of ${stats.total} open`;
    }
  }

  async function refreshReviews() {
    try {
      state.reviews = await api("/api/reviews");
    } catch {
      state.reviews = [];
    }
  }

  async function refreshCurrentSession(silent) {
    if (!state.seat) return;
    try {
      const session = await api(`/api/sessions/current?sessionId=${encodeURIComponent(state.seat.sessionId)}`);
      state.session = session;
      if (session.status === "COMPLETED" && checkoutDialog.open) {
        checkoutDialog.close();
      }
      render();
    } catch (err) {
      if (!silent) {
        state.error = err.message;
        render();
      }
    }
  }

  function startDurationClock() {
    clearInterval(state.durationTimer);
    state.durationTimer = setInterval(() => {
      const clock = document.getElementById("session-duration");
      if (!clock || !state.session || state.session.status !== "ACTIVE") return;
      const started = Date.parse(state.session.startedAt);
      if (!Number.isNaN(started)) {
        clock.textContent = formatDuration(Math.floor((Date.now() - started) / 1000));
      }
    }, 1000);
  }

  function tableSilhouette(t) {
    const chairsPerSide = Math.max(1, Math.floor(t.capacity / 2));
    const pips = Array.from({ length: chairsPerSide }, () => '<span class="chair-pip"></span>').join("");
    const wide = t.capacity >= 6 ? "wide" : "";
    return `
      <div class="table-silhouette" aria-hidden="true">
        <div class="chair-row">${pips}</div>
        <div class="table-surface-box ${wide}">${esc(t.label)}</div>
        <div class="chair-row">${pips}</div>
      </div>
    `;
  }

  function spicePipsHtml(level) {
    const n = Math.max(1, Math.min(5, Number(level || 1)));
    return `<span class="spice-pips" title="Spice ${n}/5">${Array.from({ length: 5 }, (_, i) =>
      `<span class="spice-pip ${i < n ? "hot" : ""}"></span>`
    ).join("")}</span>`;
  }

  function renderDishCard(item, orderedQty) {
    const catLabels = {
      STARTER: "Starter",
      MAIN_COURSE: "Main Course",
      DESSERT: "Dessert",
      DRINK: "Drink"
    };
    const qty = state.picks[item.id] || 1;
    const isVeg = item.type === "VEG";
    const img = DISH_IMAGES[item.name] || "/public/dishes/veg-biryani.jpg";
    const fallback = DISH_FALLBACKS[item.name] || "/public/dishes/veg-biryani.svg";

    return `
      <article class="dish-card">
        <div class="dish-photo-wrap">
          <img src="${img}" alt="${esc(item.name)}" loading="lazy" onerror="this.onerror=null;this.src='${fallback}';">
          <span class="dish-cat-tag">${esc(catLabels[item.category] || item.category)}</span>
          ${orderedQty > 0 ? `<span class="in-order-tag num">In order ×${orderedQty}</span>` : ""}
        </div>
        <div class="dish-body">
          <div class="dish-title-row">
            <span class="diet-dot ${isVeg ? "veg" : "nonveg"}" title="${isVeg ? "Vegetarian" : "Non-Vegetarian"}"></span>
            <h3>${esc(item.name)}</h3>
          </div>
          <div class="dish-sub">
            <span>${isVeg ? "Vegetarian" : "Non-Vegetarian"}</span>
            ${!isVeg && item.spice ? `<span>·</span>${spicePipsHtml(item.spice)}` : ""}
          </div>
        </div>
        <div class="dish-foot">
          <span class="dish-price num">${money(item.price)}</span>
          <div style="display:flex;align-items:center;gap:8px;">
            <div class="stepper">
              <button type="button" data-dec="${item.id}" aria-label="Decrease">−</button>
              <span>${qty}</span>
              <button type="button" class="inc-btn" data-inc="${item.id}" aria-label="Increase">+</button>
            </div>
            <button type="button" class="btn btn-primary btn-sm" data-add="${item.id}">Add</button>
          </div>
        </div>
      </article>
    `;
  }

  // ==================== VIEW 1: EDITORIAL RESTAURANT HOMEPAGE ====================

  function renderHomeView(animateLanding) {
    connectSse("tables");
    const availCount = state.tables.filter(t => t.status === "AVAILABLE").length;
    const totalCount = state.tables.length || 10;

    // 4 signature dishes rendered in printed tasting-menu ledger style
    const signatureFour = state.menu.slice(0, 4);
    const tastingHtml = signatureFour.map((item, idx) => {
      const img = DISH_IMAGES[item.name] || "/public/dishes/veg-biryani.jpg";
      const isVeg = item.type === "VEG";
      const note = TASTING_NOTES[item.name] || (isVeg ? "Freshly prepared vegetarian speciality." : "Slow-roasted with house spices.");
      return `
        <article class="tasting-item reveal-item" style="--d:${idx};">
          <img class="tasting-thumb" src="${img}" alt="${esc(item.name)}">
          <div>
            <div class="tasting-line">
              <span class="diet-dot ${isVeg ? "veg" : "nonveg"}" title="${isVeg ? "Vegetarian" : "Non-Vegetarian"}"></span>
              <h3>${esc(item.name)}</h3>
              <span class="tasting-dots" aria-hidden="true"></span>
              <span class="tasting-price num">${money(item.price)}</span>
            </div>
            <p class="tasting-note">${esc(note)}</p>
          </div>
        </article>
      `;
    }).join("");

    // Group tables by dining space for live availability badges
    const spacesMap = new Map();
    for (const t of state.tables) {
      if (!spacesMap.has(t.zone)) {
        spacesMap.set(t.zone, { zone: t.zone, label: t.zoneLabel, open: 0, total: 0 });
      }
      const entry = spacesMap.get(t.zone);
      entry.total++;
      if (t.status === "AVAILABLE") entry.open++;
    }

    const reviews = state.reviews.length ? state.reviews.slice(0, 3) : FALLBACK_REVIEWS;

    app.innerHTML = `
      <section class="hero-band ${animateLanding ? "anim-enter" : ""}">
        <div class="hero-inner">
          <div class="hero-copy">
            <p class="eyebrow" style="color:#e4be73;">Est. 2026 · Clay Oven &amp; Spice House</p>
            <h1>Slow-cooked heritage,<br><em>served warm.</em></h1>
            <p class="hero-lead">
              At Spice Garden, mornings begin by dry-roasting whole spices and stoking the charcoal tandoor. Join us in the courtyard or dining hall for food made to share around one table.
            </p>
            <div class="hero-actions">
              <button class="btn btn-brass" type="button" data-go-tables="book">Book a Table →</button>
              <button class="btn btn-hero-outline" type="button" data-go-tables="join">Join with Table Code</button>
              <a class="btn btn-hero-outline" href="#signature-menu">View Tasting Menu</a>
            </div>
            <div class="hero-facts">
              <div><strong id="home-open-count">${availCount} of ${totalCount} Open</strong>Live dining tables</div>
              <div><strong>16 House Dishes</strong>Veg &amp; Non-Veg kitchen</div>
              <div><strong>Shared Table QR</strong>Order together from any phone</div>
            </div>
          </div>
          <div class="hero-media">
            <div class="hero-photo-frame">
              <img src="/public/dishes/hero-feast.jpg" alt="Spice Garden Indian dining feast on wooden table">
            </div>
            <div class="hero-badge">
              <strong>4.8 ★</strong>
              <span>Guest favourite kitchen</span>
            </div>
          </div>
        </div>
      </section>

      <section class="editorial-section" id="story">
        <div class="story-grid">
          <div class="story-photo-card reveal-item reveal-left reveal-scale" style="--d:0;">
            <img src="/public/dishes/tandoor-spices.jpg" alt="Whole roasted Indian spices and tandoor prep at Spice Garden">
            <div class="story-caption">
              <span>Whole spices roasted fresh daily</span>
              <span class="mono">07:00 AM PREP</span>
            </div>
          </div>
          <div class="story-copy">
            <p class="eyebrow reveal-item reveal-right" style="--d:0;">Our Craft &amp; Story</p>
            <h2 class="reveal-item reveal-right" style="--d:1;">Patience is our primary ingredient.</h2>
            <p class="reveal-item reveal-right" style="--d:2;">
              Every curry base simmers slowly in heavy copper handis, while skewers of cottage cheese and spiced meats are blistered over glowing charcoal moments before reaching your table.
            </p>
            <p class="reveal-item reveal-right" style="--d:3;">
              Whether you’re catching up over masala chai or hosting a full courtyard dinner, everyone at your table can scan one code, order at their own pace, and settle together seamlessly.
            </p>
            <blockquote class="pull-quote reveal-item" style="--d:4;">
              “Good Indian dining isn’t rushed — it’s warm bread passed across the table, fragrant saffron steam, and shared plates still sizzling from the clay oven.”
            </blockquote>
          </div>
        </div>
      </section>

      <section class="tasting-band" id="signature-menu">
        <div class="editorial-section">
          <div class="tasting-ledger reveal-item reveal-scale" style="--d:0;">
            <div class="tasting-header">
              <div>
                <p class="eyebrow">Signature Tasting Selection</p>
                <h2>From the Tandoor &amp; Handi</h2>
              </div>
              <p class="muted" style="margin:0;max-width:340px;font-size:13px;">
                Four house favourites — explore all ${state.menu.length || 16} starters, mains, desserts, and drinks once you take your seat.
              </p>
            </div>
            <div class="tasting-grid">
              ${tastingHtml || '<p class="muted">Loading signature dishes…</p>'}
            </div>
            <div class="tasting-footer reveal-item" style="--d:2;">
              <span class="muted" style="font-size:13px;">
                Includes vegetarian &amp; non-vegetarian starters, slow-cooked mains, desserts &amp; house chai.
              </span>
              <button class="btn btn-primary" type="button" data-go-tables="book">
                Select a Table to Order All ${state.menu.length || 16} Dishes →
              </button>
            </div>
          </div>
        </div>
      </section>

      <section class="editorial-section" id="spaces">
        <div class="reveal-item" style="--d:0;">
          <p class="eyebrow">Four Distinct Rooms</p>
          <h2 style="font-size:clamp(28px,3.4vw,40px);">Choose your atmosphere</h2>
          <p class="muted" style="margin:6px 0 0;max-width:560px;">
            From sunlit window booths to our lantern-lit garden courtyard, check live table availability across every room.
          </p>
        </div>

        <div class="spaces-layout">
          <div class="spaces-feature-photo reveal-item reveal-left reveal-scale" style="--d:0;">
            <img src="/public/dishes/interior-dining.jpg" alt="Spice Garden warm candlelit dining room interior">
          </div>
          <div class="spaces-cards">
            ${[...spacesMap.values()].map((sp, idx) => `
              <article class="space-card reveal-item" style="--d:${idx};">
                <span class="space-status num" data-space-status="${esc(sp.zone)}">${sp.open} of ${sp.total} open</span>
                <h3>${esc(sp.label)}</h3>
                <p class="muted" style="margin:0;font-size:13px;line-height:1.55;">
                  ${esc(SPACE_DESCRIPTIONS[sp.zone] || "Comfortable dining tables with table-side QR ordering.")}
                </p>
                <button type="button" class="btn btn-outline btn-sm" style="margin-top:auto;" data-go-tables="book">
                  View Tables →
                </button>
              </article>
            `).join("")}
          </div>
        </div>
      </section>

      <section class="reviews-band" id="reviews">
        <div class="editorial-section">
          <div class="reveal-item" style="display:flex;flex-wrap:wrap;justify-content:space-between;align-items:flex-end;gap:16px;--d:0;">
            <div>
              <p class="eyebrow" style="color:#e4be73;">From Our Guests</p>
              <h2>Voices from the dining room</h2>
            </div>
            <button type="button" class="btn btn-brass" data-go-tables="book">Reserve Your Seat →</button>
          </div>
          <div class="reviews-grid">
            ${reviews.map((r, idx) => `
              <article class="review-card reveal-item" style="--d:${idx + 1};">
                <div style="color:#e4be73;font-size:15px;">${"★".repeat(r.rating)}${"☆".repeat(5 - r.rating)}</div>
                <p class="review-quote">“${esc(r.comment || "Wonderful flavours and effortless table sharing.")}”</p>
                <div class="review-author">
                  <strong>${esc(r.customerName)}</strong> · Table ${r.tableNumber} · ${esc(r.category)}
                </div>
              </article>
            `).join("")}
          </div>
        </div>
      </section>

      <footer class="footer-rich reveal-item" style="--d:0;">
        <div class="footer-grid">
          <div>
            <strong style="font-family:var(--serif);font-size:20px;color:#fff;">Spice Garden</strong>
            <p style="margin:10px 0 0;font-size:13px;line-height:1.7;max-width:300px;">
              Honest Indian flavours cooked fresh in small batches, served with shared table billing from any phone.
            </p>
          </div>
          <div>
            <h4>Hours &amp; Kitchen</h4>
            <ul>
              <li>Tue – Sun: 11:30 AM – 11:30 PM</li>
              <li>Monday: Closed for spice prep</li>
              <li>Last tandoor order: 11:00 PM</li>
            </ul>
          </div>
          <div>
            <h4>Dining Rooms</h4>
            <ul>
              <li>Window Alcove (2 &amp; 4 seats)</li>
              <li>Garden Courtyard (4 &amp; 6 seats)</li>
              <li>Main Hall &amp; Corner Lounge</li>
            </ul>
          </div>
          <div>
            <h4>Guest Actions</h4>
            <ul>
              <li><a href="#" data-go-tables="book" style="color:#e4be73;">Book a Table →</a></li>
              <li><a href="#" data-go-tables="join">Join with Table Code</a></li>
              <li><a href="/admin">Staff &amp; Admin Console</a></li>
            </ul>
          </div>
        </div>
        <div class="footer-bottom">
          <span>Spice Garden · Indian Kitchen &amp; Dining</span>
          <span>Live floor availability · Table-side QR ordering</span>
        </div>
      </footer>

      <div class="mobile-home-cta" aria-label="Quick table booking actions">
        <button class="btn btn-primary btn-sm" type="button" data-go-tables="book">Book a Table →</button>
        <button class="btn btn-outline btn-sm" type="button" data-go-tables="join">Join Code / QR</button>
      </div>
    `;
  }

  // ==================== VIEW 2: DEDICATED FLOOR PLAN & JOIN TABLE ====================

  function renderTablesView(animateEntry) {
    connectSse("tables");
    const zones = [];
    const byZone = new Map();
    for (const t of state.tables) {
      if (!byZone.has(t.zoneLabel)) {
        byZone.set(t.zoneLabel, []);
        zones.push(t.zoneLabel);
      }
      byZone.get(t.zoneLabel).push(t);
    }

    const availCount = state.tables.filter(t => t.status === "AVAILABLE").length;
    const occCount = state.tables.length - availCount;

    const floorHtml = `
      <div class="room-shell">
        <div class="room-wall-label">
          <span>North Window Wall · Entrance</span>
          <span>Kitchen Pass &amp; Counter →</span>
        </div>
        ${zones.map(zone => `
          <div class="zone-block">
            <div class="zone-title">${esc(zone)}</div>
            <div class="tables-row">
              ${byZone.get(zone).map(t => {
                const avail = t.status === "AVAILABLE";
                const selected = state.selectedTable === t.tableNumber;
                const cls = selected ? "selected" : (avail ? "available" : "occupied");
                return `
                  <button
                    type="button"
                    class="table-tile ${cls}"
                    data-table="${t.tableNumber}"
                    data-status="${t.status}"
                    aria-label="Table ${t.tableNumber}, ${t.capacity} seats, ${avail ? "Available" : "Occupied"}"
                  >
                    <div class="table-shape-wrap">
                      ${tableSilhouette(t)}
                      <span class="table-badge">${avail ? "Available" : "Occupied"}</span>
                    </div>
                    <div class="table-meta">
                      ${t.capacity} seats · ${avail ? "Tap to book" : `${t.guestCount} seated`}
                    </div>
                  </button>
                `;
              }).join("")}
            </div>
          </div>
        `).join("")}
      </div>
    `;

    const rightPanel = state.entryMode === "book" ? `
      <div class="card seating-form-card">
        <p class="eyebrow">Step 1 · Book a Table</p>
        <h3>${state.selectedTable ? `Table ${state.selectedTable} selected` : "Select a green table"}</h3>
        <p class="muted" style="font-size:13px;margin:8px 0 18px;">
          ${state.selectedTable
            ? "Enter your name below to open a shared table session. You’ll receive a join code and QR code for friends."
            : "Available tables appear in green. Occupied tables in red cannot be double-booked."}
        </p>
        <form id="book-form" class="field" style="gap:14px;">
          <div class="field">
            <label for="book-name">Your name</label>
            <input id="book-name" type="text" maxlength="80" required placeholder="e.g. Aarav">
          </div>
          <button class="btn btn-primary btn-block" type="submit" ${state.selectedTable ? "" : "disabled"}>
            ${state.selectedTable ? `Book Table ${state.selectedTable} & Start Ordering` : "Select an available table first"}
          </button>
          <p class="error-msg" id="entry-error">${esc(state.error)}</p>
        </form>
      </div>
    ` : `
      <div class="card seating-form-card join-priority">
        <p class="eyebrow">Join Friends</p>
        <h3>Join an Active Table</h3>
        <p class="muted" style="font-size:13px;margin:8px 0 18px;">
          Already seated with friends? Enter the table’s Join Code (or scan their QR code) to order independently onto the shared bill.
        </p>
        <form id="join-form" class="field" style="gap:14px;">
          <div class="field">
            <label for="join-code">Table Join Code</label>
            <input id="join-code" type="text" maxlength="16" required class="mono" style="text-transform:uppercase;" value="${esc(state.joinPrefill)}" placeholder="e.g. T4-9K2M">
          </div>
          <div class="field">
            <label for="join-name">Your name</label>
            <input id="join-name" type="text" maxlength="80" required placeholder="e.g. Priya">
          </div>
          <button class="btn btn-primary btn-block" type="submit">Join Shared Table</button>
          <p class="error-msg" id="entry-error">${esc(state.error)}</p>
        </form>
      </div>
    `;

    app.innerHTML = `
      <div class="page ${animateEntry ? "anim-stagger" : ""}">
        <div style="margin-bottom:14px;">
          <button type="button" class="btn btn-outline btn-sm" id="back-home-btn">← Back to Spice Garden Home</button>
        </div>

        <div class="seating-intro">
          <div>
            <p class="eyebrow">Live Dining Floor</p>
            <h1 style="font-size:clamp(28px,3.4vw,38px);">Choose your table</h1>
            <p class="muted" style="margin:6px 0 0;">
              ${availCount} available · ${occCount} occupied · Select a green table to open a shared bill or join with a code
            </p>
          </div>
          <div class="mode-tabs" role="tablist">
            <button class="mode-tab ${state.entryMode === "book" ? "active" : ""}" type="button" data-entry="book">Book a Table</button>
            <button class="mode-tab ${state.entryMode === "join" ? "active" : ""}" type="button" data-entry="join">Join with Code / QR</button>
          </div>
        </div>

        <div class="seating-grid">
          <section class="card">
            <div class="floor-header">
              <div>
                <h3 style="font-size:21px;">Architectural Floor Plan</h3>
                <p class="muted" style="margin:2px 0 0;font-size:13px;">Chair pips show seating capacity · Live updates</p>
              </div>
              <div class="legend">
                <span class="legend-item"><span class="swatch available"></span> Available</span>
                <span class="legend-item"><span class="swatch occupied"></span> Occupied</span>
                <span class="legend-item"><span class="swatch selected"></span> Selected</span>
              </div>
            </div>
            ${state.tables.length ? floorHtml : '<p class="muted">Loading tables…</p>'}
          </section>
          ${rightPanel}
        </div>
      </div>
    `;
  }

  // ==================== VIEW 3: COMPLETED RECEIPT + FEEDBACK ====================

  function renderCompletedView(session) {
    const fbHtml = state.feedbackSubmitted ? `
      <div class="card" style="background:var(--avail-bg);border-color:var(--avail-border);">
        <p class="eyebrow" style="color:var(--avail-ink);">Feedback Saved</p>
        <h3 style="color:var(--avail-ink);">Thank you for dining with us</h3>
        <p style="margin:8px 0 14px;font-size:14px;">Your ${state.feedbackRating}★ rating and feedback have been shared with the Spice Garden team.</p>
        <button class="btn btn-primary" type="button" id="new-visit-btn">Return to Spice Garden Home</button>
      </div>
    ` : `
      <div class="card">
        <p class="eyebrow">Share Your Experience</p>
        <h3>How was your meal?</h3>
        <p class="muted" style="font-size:13px;margin:6px 0 14px;">Rate your experience at Table ${session.tableNumber}. Feedback appears immediately on our Admin Console and Homepage.</p>
        <form id="feedback-form" class="field" style="gap:14px;">
          <div class="field">
            <label>Your rating</label>
            <div class="star-row" id="star-row">
              ${[1, 2, 3, 4, 5].map(n => `
                <button type="button" class="star-btn ${n <= state.feedbackRating ? "on" : ""}" data-star="${n}" aria-label="${n} stars">★</button>
              `).join("")}
            </div>
          </div>
          <div class="field">
            <label for="fb-category">Highlight</label>
            <select id="fb-category">
              ${["Overall", "Food", "Service", "Ambience"].map(c =>
                `<option value="${c}" ${state.feedbackCategory === c ? "selected" : ""}>${c}</option>`
              ).join("")}
            </select>
          </div>
          <div class="field">
            <label for="fb-comment">Comments (optional)</label>
            <textarea id="fb-comment" maxlength="1000" placeholder="Tell us what you loved or how we can improve…"></textarea>
          </div>
          <p class="error-msg" id="fb-error"></p>
          <div style="display:flex;gap:10px;flex-wrap:wrap;">
            <button class="btn btn-primary" type="submit">Submit Feedback</button>
            <button class="btn btn-outline" type="button" id="new-visit-btn">Skip & Return Home</button>
          </div>
        </form>
      </div>
    `;

    app.innerHTML = `
      <div class="page">
        <div style="max-width:760px;margin:0 auto;display:grid;gap:20px;">
          <div class="card" style="border-color:var(--avail-border);">
            <p class="eyebrow" style="color:var(--avail-ink);">Payment Confirmed · Table Released</p>
            <h1 style="font-size:30px;">Table ${session.tableNumber} settled</h1>
            <p class="muted" style="margin:6px 0 14px;font-size:14px;">
              ${state.receiptNote ? esc(state.receiptNote) + " · " : ""}Session #${session.sessionId} · Paid via <strong>${esc(session.paymentMode)}</strong> · Duration ${formatDuration(session.durationSeconds)}
            </p>
            <div style="border-top:1px solid var(--line);padding-top:12px;">
              ${session.consolidatedLines.map(l => `
                <div class="order-line">
                  <span>${esc(l.name)} × ${l.quantity}</span>
                  <span class="num">${money(l.lineTotal)}</span>
                </div>
              `).join("")}
              <div class="totals-box">
                <div class="total-row"><span>Subtotal</span><span class="num">${money(session.subtotal)}</span></div>
                <div class="total-row"><span>GST (5%)</span><span class="num">${money(session.gst)}</span></div>
                <div class="total-row grand"><span>Total Paid</span><span class="num">${money(session.total)}</span></div>
              </div>
            </div>
          </div>
          ${fbHtml}
        </div>
      </div>
    `;
  }

  // ==================== VIEW 4: ACTIVE SEATED WORKSPACE ====================

  function orderProgressHtml(status) {
    const steps = ["PLACED", "PREPARING", "READY", "SERVED"];
    const labels = ["Placed", "Preparing", "Ready", "Served"];
    const idx = Math.max(0, steps.indexOf(status));
    return `
      <div class="order-progress">
        ${steps.map((s, i) => `
          <div class="prog-step ${i < idx ? "done" : (i === idx ? "current" : "")}" data-step="${s}">${labels[i]}</div>
        `).join("")}
      </div>
    `;
  }

  function renderSessionView(session) {
    connectSse("session:" + session.sessionId);

    if (session.status !== "ACTIVE") {
      renderCompletedView(session);
      return;
    }

    const categories = ["ALL", "STARTER", "MAIN_COURSE", "DESSERT", "DRINK"];
    const catLabels = {
      ALL: "All dishes",
      STARTER: "Starters",
      MAIN_COURSE: "Main course",
      DESSERT: "Desserts",
      DRINK: "Drinks"
    };

    const q = state.searchQuery.trim().toLowerCase();
    const filteredMenu = state.menu.filter(item => {
      if (state.category !== "ALL" && item.category !== state.category) return false;
      if (state.dietFilter !== "ALL" && item.type !== state.dietFilter) return false;
      if (q && !item.name.toLowerCase().includes(q)) return false;
      return true;
    });

    const myCustomerId = state.seat.customerId;
    const myOrders = (session.customers.find(c => c.customerId === myCustomerId)?.orders || []);
    const hasMyPlacedOrder = myOrders.some(o => o.status === "PLACED" && o.lines.length > 0);

    const myItemCounts = new Map();
    for (const o of myOrders) {
      if (o.status === "CANCELLED") continue;
      for (const l of o.lines) {
        myItemCounts.set(l.itemId, (myItemCounts.get(l.itemId) || 0) + l.quantity);
      }
    }

    const byGuestHtml = session.customers.map(c => {
      const isMe = c.customerId === myCustomerId;
      const color = avatarColor(c.customerId);
      const ordersHtml = c.orders.length ? c.orders.map(o => `
        <div class="order-block">
          <div style="display:flex;justify-content:space-between;align-items:center;">
            <span class="muted" style="font-size:11px;">Order #${o.orderId}</span>
            <span class="status-tag status-${o.status}">${o.status}</span>
          </div>
          ${orderProgressHtml(o.status)}
          ${o.lines.map(l => `
            <div class="order-line">
              <span>${esc(l.name)} × ${l.quantity}</span>
              <span class="num" style="display:inline-flex;align-items:center;gap:6px;">
                ${money(l.lineTotal)}
                ${isMe && o.status === "PLACED"
                  ? `<button type="button" class="remove-btn" data-remove-order="${o.orderId}" data-remove-line="${l.lineIndex}" title="Remove item">×</button>`
                  : ""}
              </span>
            </div>
          `).join("")}
        </div>
      `).join("") : `<p class="muted" style="margin:4px 0 0;font-size:12px;">No dishes ordered yet.</p>`;

      return `
        <div class="customer-group">
          <div class="customer-group-head">
            <span style="display:inline-flex;align-items:center;gap:8px;">
              <span class="guest-avatar" style="background:${color};">${esc(initials(c.name))}</span>
              <span>${esc(c.name)} ${isMe ? "(You)" : ""}</span>
            </span>
            <span class="num">${money(c.subtotal)}</span>
          </div>
          ${ordersHtml}
        </div>
      `;
    }).join("");

    const combinedHtml = session.consolidatedLines.length
      ? session.consolidatedLines.map(l => `
          <div class="order-line" style="padding:7px 0;border-bottom:1px solid var(--line);">
            <span>${esc(l.name)} × ${l.quantity}</span>
            <span class="num" style="font-weight:600;">${money(l.lineTotal)}</span>
          </div>
        `).join("")
      : `<p class="muted" style="font-size:13px;">Your table hasn’t added any items yet. Pick dishes from the menu.</p>`;

    app.innerHTML = `
      <div class="page">
        <section class="session-banner">
          <div>
            <p class="eyebrow" style="color:#e4be73;margin-bottom:4px;">Active Shared Table Session</p>
            <h2>Table ${session.tableNumber} · ${esc(session.customers.length)} ${session.customers.length === 1 ? "Guest" : "Guests"}</h2>
            <div class="session-meta-row">
              <span>Join Code:</span>
              <span class="code-chip">${esc(session.joinCode)}</span>
              <span>·</span>
              <span>Seated: <strong id="session-duration" class="num">${formatDuration(session.durationSeconds)}</strong></span>
            </div>
            <div class="guest-pills">
              ${session.customers.map(c => `
                <span class="guest-pill ${c.customerId === myCustomerId ? "me" : ""}">
                  <span class="guest-avatar" style="background:${avatarColor(c.customerId)};">${esc(initials(c.name))}</span>
                  ${esc(c.name)}${c.customerId === myCustomerId ? " (You)" : ""}
                </span>
              `).join("")}
            </div>
          </div>
          <div style="display:flex;gap:8px;flex-wrap:wrap;">
            <button class="btn btn-brass btn-sm" type="button" id="open-qr-btn">Invite via QR / Code</button>
            <button class="btn btn-outline btn-sm" type="button" id="leave-view-btn" style="background:transparent;color:#fff;border-color:#4b7460;">Switch Table</button>
          </div>
        </section>

        <div class="workspace-grid">
          <section>
            <div class="menu-search-row">
              <input id="menu-search" type="search" value="${esc(state.searchQuery)}" placeholder="Search dishes (e.g. Biryani, Tikka, Chai)…">
              <div class="filter-pills">
                <button type="button" class="pill ${state.dietFilter === "ALL" ? "active" : ""}" data-diet="ALL">All</button>
                <button type="button" class="pill ${state.dietFilter === "VEG" ? "active" : ""}" data-diet="VEG">Veg</button>
                <button type="button" class="pill ${state.dietFilter === "NON_VEG" ? "active" : ""}" data-diet="NON_VEG">Non-Veg</button>
              </div>
            </div>

            <div class="menu-controls">
              <div class="filter-pills">
                ${categories.map(cat => `
                  <button type="button" class="pill ${state.category === cat ? "active" : ""}" data-cat="${cat}">
                    ${catLabels[cat]}
                  </button>
                `).join("")}
              </div>
            </div>

            <div class="menu-grid">
              ${filteredMenu.length
                ? filteredMenu.map(item => renderDishCard(item, myItemCounts.get(item.id) || 0)).join("")
                : `<div class="card" style="grid-column:1/-1;"><p class="muted" style="margin:0;">No dishes match your filter.</p></div>`}
            </div>
          </section>

          <aside class="card bill-panel" id="shared-bill-panel">
            <div style="display:flex;justify-content:space-between;align-items:center;">
              <div>
                <p class="eyebrow">Live Shared Bill</p>
                <h3 style="font-size:22px;">Table ${session.tableNumber} Order</h3>
              </div>
              <span class="num muted" style="font-size:12px;font-weight:700;">${session.itemCount} items</span>
            </div>

            <div class="bill-tabs">
              <button type="button" class="bill-tab ${state.billTab === "by-guest" ? "active" : ""}" data-billtab="by-guest">By Guest (${session.customers.length})</button>
              <button type="button" class="bill-tab ${state.billTab === "combined" ? "active" : ""}" data-billtab="combined">Combined Bill</button>
            </div>

            <div>
              ${state.billTab === "by-guest" ? byGuestHtml : combinedHtml}
            </div>

            <div class="totals-box">
              <div class="total-row"><span>Subtotal</span><span class="num">${money(session.subtotal)}</span></div>
              <div class="total-row"><span>GST (5%)</span><span class="num">${money(session.gst)}</span></div>
              <div class="total-row grand"><span>Shared Total</span><span class="num">${money(session.total)}</span></div>
            </div>

            <p class="error-msg" id="workspace-error">${esc(state.error)}</p>

            <div style="display:grid;gap:8px;margin-top:12px;">
              ${hasMyPlacedOrder ? `
                <button type="button" class="btn btn-brass btn-block" id="send-kitchen-btn">
                  Send My Pending Items to Kitchen
                </button>
              ` : ""}
              <button type="button" class="btn btn-primary btn-block" id="open-checkout-btn" ${session.itemCount > 0 ? "" : "disabled"}>
                Proceed to Checkout (${money(session.total)})
              </button>
            </div>
          </aside>
        </div>

        <div class="mobile-bill-bar" aria-label="Shared bill quick summary">
          <div>
            <div style="font-size:11px;color:#d6e2da;">Table ${session.tableNumber} · ${session.itemCount} item(s)</div>
            <strong class="num" style="font-size:16px;">${money(session.total)}</strong>
          </div>
          <div style="display:flex;gap:8px;">
            <button type="button" class="btn btn-outline btn-sm" id="jump-bill-btn" style="background:transparent;color:#fff;border-color:#4b7460;">View Bill</button>
            <button type="button" class="btn btn-brass btn-sm" id="mobile-checkout-btn" ${session.itemCount > 0 ? "" : "disabled"}>Checkout</button>
          </div>
        </div>
      </div>
    `;
  }

  function observeReveals() {
    const targets = document.querySelectorAll(".reveal, .reveal-item");
    if (!targets.length) return;
    if (typeof IntersectionObserver !== "function") {
      targets.forEach(el => el.classList.add("in-view"));
      return;
    }
    const io = new IntersectionObserver(entries => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          entry.target.classList.add("in-view");
          io.unobserve(entry.target);
        }
      }
    }, { threshold: 0.12, rootMargin: "0px 0px -30px 0px" });
    targets.forEach(el => io.observe(el));
  }

  let scrollTicking = false;
  function updateScrollProgress() {
    const bar = document.getElementById("scroll-progress");
    if (!bar) return;
    const max = Math.max(1, document.documentElement.scrollHeight - window.innerHeight);
    const ratio = Math.min(1, Math.max(0, window.scrollY / max));
    bar.style.transform = `scaleX(${ratio.toFixed(4)})`;
  }
  window.addEventListener("scroll", () => {
    if (!scrollTicking) {
      scrollTicking = true;
      window.requestAnimationFrame(() => {
        updateScrollProgress();
        scrollTicking = false;
      });
    }
  }, { passive: true });

  function render() {
    const activeSearch = document.activeElement && document.activeElement.id === "menu-search";
    const selStart = activeSearch ? document.activeElement.selectionStart : null;
    const showMobileBar = Boolean(state.seat && state.session && state.session.status === "ACTIVE");
    document.body.classList.toggle("has-mobile-bar", showMobileBar);

    const nextMode = (state.seat && state.session)
      ? (state.session.status === "ACTIVE" ? "session" : "completed")
      : (state.pageView === "tables" ? "tables" : "home");

    const isHome = nextMode === "home";
    document.body.classList.toggle("has-mobile-home", isHome);
    if (topbarNav) topbarNav.style.display = isHome ? "" : "none";
    if (navTablesBtn) navTablesBtn.hidden = !isHome;

    const isEntering = state.lastViewMode !== nextMode;
    state.lastViewMode = nextMode;

    if (nextMode === "home") {
      clearInterval(state.durationTimer);
      state.lastBillTotal = null;
      renderHomeView(isEntering);
      observeReveals();
      updateScrollProgress();
    } else if (nextMode === "tables") {
      clearInterval(state.durationTimer);
      state.lastBillTotal = null;
      renderTablesView(isEntering);
      updateScrollProgress();
    } else {
      const prevTotal = state.lastBillTotal;
      const newTotal = state.session.total;
      renderSessionView(state.session);
      startDurationClock();
      if (prevTotal !== null && Number(prevTotal) !== Number(newTotal)) {
        const grandRow = document.querySelector("#shared-bill-panel .total-row.grand");
        if (grandRow) grandRow.classList.add("bill-updated");
      }
      state.lastBillTotal = newTotal;
      if (activeSearch) {
        const searchEl = document.getElementById("menu-search");
        if (searchEl) {
          searchEl.focus();
          if (selStart !== null) searchEl.setSelectionRange(selStart, selStart);
        }
      }
    }
  }

  // ==================== DIALOGS ====================

  function openQrDialog() {
    if (!state.session) return;
    const joinUrl = `${window.location.origin}/?join=${encodeURIComponent(state.session.joinCode)}`;
    document.getElementById("qr-table-title").textContent = `Table ${state.session.tableNumber}`;
    document.getElementById("qr-code-text").textContent = state.session.joinCode;
    document.getElementById("qr-url-text").textContent = joinUrl;

    const container = document.getElementById("qr-container");
    if (typeof window.qrcode === "function") {
      const qr = window.qrcode(0, "M");
      qr.addData(joinUrl);
      qr.make();
      container.innerHTML = qr.createSvgTag({ cellSize: 5, margin: 2 });
    } else {
      container.innerHTML = `<p class="mono">${esc(state.session.joinCode)}</p>`;
    }
    qrDialog.showModal();
  }

  function buildUpiQrSvg(amount) {
    if (typeof window.qrcode !== "function") return "";
    const qr = window.qrcode(0, "M");
    qr.addData(`upi://pay?pa=spicegarden@upi&pn=Spice%20Garden&am=${Number(amount).toFixed(2)}&cu=INR`);
    qr.make();
    return qr.createSvgTag({ cellSize: 3, margin: 1 });
  }

  function renderCheckoutDialog() {
    const s = state.session;
    if (!s) return;
    document.getElementById("checkout-table-label").textContent = `Table ${s.tableNumber}`;
    document.getElementById("checkout-error").textContent = "";

    const guestCount = Math.max(1, s.customers.length);
    const equalShare = s.total / guestCount;
    const myCustomer = s.customers.find(c => c.customerId === state.seat.customerId);
    const myShareWithGst = myCustomer ? myCustomer.subtotal * 1.05 : 0;

    let simBoxHtml = "";
    if (state.payMode === "UPI") {
      simBoxHtml = `
        <div class="pay-sim-box pay-upi-qr">
          ${buildUpiQrSvg(s.total)}
          <div>
            <strong>Scan Simulated UPI QR</strong>
            <div class="muted" style="font-size:12px;margin-top:2px;">VPA: <span class="mono">spicegarden@upi</span></div>
            <div class="muted" style="font-size:12px;">Amount: <strong class="num">${money(s.total)}</strong></div>
          </div>
        </div>
      `;
    } else if (state.payMode === "CARD") {
      simBoxHtml = `
        <div class="pay-sim-box">
          <strong>Simulated Card Terminal</strong>
          <div class="mono muted" style="font-size:12px;margin-top:4px;">VISA / MASTERCARD · •••• •••• •••• 4242 · Tap or Swipe Ready</div>
        </div>
      `;
    } else {
      simBoxHtml = `
        <div class="pay-sim-box">
          <strong>Cash Counter Settlement</strong>
          <div class="muted" style="font-size:12px;margin-top:4px;">Counter Slip <span class="mono">#T${s.tableNumber}-${s.sessionId}</span> · Pay <strong class="num">${money(s.total)}</strong> at counter.</div>
        </div>
      `;
    }

    document.getElementById("checkout-summary").innerHTML = `
      <div style="border:1px solid var(--line);border-radius:6px;padding:12px;margin-bottom:12px;max-height:150px;overflow-y:auto;">
        ${s.consolidatedLines.map(l => `
          <div class="order-line">
            <span>${esc(l.name)} × ${l.quantity}</span>
            <span class="num">${money(l.lineTotal)}</span>
          </div>
        `).join("")}
      </div>

      <div class="split-helper">
        <strong>Bill Split Helper:</strong>
        Your items (incl. 5% GST): <strong class="num">${money(myShareWithGst)}</strong>
        ${guestCount > 1 ? ` · Equal split (${guestCount} guests): <strong class="num">${money(equalShare)}</strong> each` : ""}
      </div>

      <div class="total-row"><span>Subtotal</span><span class="num">${money(s.subtotal)}</span></div>
      <div class="total-row"><span>GST (5%)</span><span class="num">${money(s.gst)}</span></div>
      <div class="total-row grand" style="margin-bottom:14px;"><span>Grand Total</span><span class="num">${money(s.total)}</span></div>

      <p style="font-size:12px;font-weight:700;margin:0 0 6px;color:var(--forest);">Choose Payment Method</p>
      <div class="pay-options">
        <button type="button" class="pay-option ${state.payMode === "UPI" ? "active" : ""}" data-paymode="UPI">UPI</button>
        <button type="button" class="pay-option ${state.payMode === "CARD" ? "active" : ""}" data-paymode="CARD">Card</button>
        <button type="button" class="pay-option ${state.payMode === "CASH" ? "active" : ""}" data-paymode="CASH">Cash</button>
      </div>
      ${simBoxHtml}
      <button type="button" class="btn btn-primary btn-block" id="confirm-pay-btn">Confirm Payment &amp; Release Table</button>
    `;
  }

  // ==================== EVENT HANDLERS ====================

  function openTablesView(mode) {
    state.pageView = "tables";
    state.entryMode = mode === "join" ? "join" : "book";
    state.error = "";
    window.scrollTo({ top: 0, behavior: "smooth" });
    render();
  }

  navTablesBtn.addEventListener("click", () => openTablesView("book"));
  document.getElementById("brand-home").addEventListener("click", e => {
    if (!state.seat && state.pageView === "tables") {
      e.preventDefault();
      state.pageView = "home";
      state.error = "";
      window.scrollTo({ top: 0, behavior: "smooth" });
      render();
    }
  });

  app.addEventListener("input", e => {
    if (e.target.id === "menu-search") {
      state.searchQuery = e.target.value;
      render();
    }
  });

  app.addEventListener("click", async e => {
    const goTablesBtn = e.target.closest("[data-go-tables]");
    if (goTablesBtn) {
      e.preventDefault();
      openTablesView(goTablesBtn.dataset.goTables);
      return;
    }

    if (e.target.id === "back-home-btn") {
      state.pageView = "home";
      state.error = "";
      window.scrollTo({ top: 0, behavior: "smooth" });
      render();
      return;
    }

    const entryBtn = e.target.closest("[data-entry]");
    if (entryBtn) {
      state.entryMode = entryBtn.dataset.entry;
      state.error = "";
      render();
      return;
    }

    const tableBtn = e.target.closest("[data-table]");
    if (tableBtn) {
      const tableNum = Number(tableBtn.dataset.table);
      const status = tableBtn.dataset.status;
      if (status === "OCCUPIED") {
        state.error = `Table ${tableNum} is occupied. Use "Join with Code / QR" to join guests at this table.`;
        render();
        const tile = document.querySelector(`[data-table="${tableNum}"]`);
        if (tile) {
          tile.classList.add("shake");
          setTimeout(() => tile.classList.remove("shake"), 420);
        }
        return;
      }
      state.selectedTable = tableNum;
      state.entryMode = "book";
      state.error = "";
      render();
      const input = document.getElementById("book-name");
      if (input) input.focus();
      return;
    }

    const catBtn = e.target.closest("[data-cat]");
    if (catBtn) {
      state.category = catBtn.dataset.cat;
      render();
      return;
    }

    const dietBtn = e.target.closest("[data-diet]");
    if (dietBtn) {
      state.dietFilter = dietBtn.dataset.diet;
      render();
      return;
    }

    const billTabBtn = e.target.closest("[data-billtab]");
    if (billTabBtn) {
      state.billTab = billTabBtn.dataset.billtab;
      render();
      return;
    }

    const incBtn = e.target.closest("[data-inc]");
    if (incBtn) {
      const id = Number(incBtn.dataset.inc);
      state.picks[id] = Math.min(20, (state.picks[id] || 1) + 1);
      render();
      return;
    }

    const decBtn = e.target.closest("[data-dec]");
    if (decBtn) {
      const id = Number(decBtn.dataset.dec);
      state.picks[id] = Math.max(1, (state.picks[id] || 1) - 1);
      render();
      return;
    }

    const addBtn = e.target.closest("[data-add]");
    if (addBtn) {
      const itemId = Number(addBtn.dataset.add);
      const quantity = state.picks[itemId] || 1;
      const dish = state.menu.find(m => m.id === itemId);
      try {
        const updated = await postJson("/api/sessions/order", {
          sessionId: state.seat.sessionId,
          customerId: state.seat.customerId,
          itemId,
          quantity
        });
        state.session = updated;
        state.picks[itemId] = 1;
        state.error = "";
        render();
        toast(`Added ${dish ? dish.name : "item"}`, "View Bill", () => {
          const panel = document.getElementById("shared-bill-panel");
          if (panel) panel.scrollIntoView({ behavior: "smooth", block: "start" });
        });
      } catch (err) {
        state.error = err.message;
        render();
      }
      return;
    }

    const rmBtn = e.target.closest("[data-remove-order]");
    if (rmBtn) {
      const orderId = Number(rmBtn.dataset.removeOrder);
      const lineIndex = Number(rmBtn.dataset.removeLine);
      try {
        const updated = await api(
          `/api/sessions/order?sessionId=${state.seat.sessionId}&customerId=${state.seat.customerId}&orderId=${orderId}&lineIndex=${lineIndex}`,
          { method: "DELETE" }
        );
        state.session = updated;
        state.error = "";
        render();
      } catch (err) {
        state.error = err.message;
        render();
      }
      return;
    }

    if (e.target.id === "send-kitchen-btn") {
      try {
        const updated = await postJson("/api/sessions/send-kitchen", {
          sessionId: state.seat.sessionId,
          customerId: state.seat.customerId
        });
        state.session = updated;
        state.error = "";
        render();
        toast("Sent to kitchen");
      } catch (err) {
        state.error = err.message;
        render();
      }
      return;
    }

    if (e.target.id === "open-qr-btn") {
      openQrDialog();
      return;
    }

    if (e.target.id === "open-checkout-btn" || e.target.id === "mobile-checkout-btn") {
      renderCheckoutDialog();
      checkoutDialog.showModal();
      return;
    }

    if (e.target.id === "jump-bill-btn") {
      const panel = document.getElementById("shared-bill-panel");
      if (panel) panel.scrollIntoView({ behavior: "smooth", block: "start" });
      return;
    }

    if (e.target.id === "leave-view-btn") {
      saveSeat(null);
      state.session = null;
      state.receiptNote = "";
      state.feedbackSubmitted = false;
      state.error = "";
      state.pageView = "tables";
      await refreshTables();
      return;
    }

    if (e.target.id === "new-visit-btn") {
      saveSeat(null);
      state.session = null;
      state.receiptNote = "";
      state.feedbackSubmitted = false;
      state.error = "";
      state.pageView = "home";
      await Promise.all([refreshTables(), refreshReviews()]);
      render();
      return;
    }

    const starBtn = e.target.closest("[data-star]");
    if (starBtn) {
      state.feedbackRating = Number(starBtn.dataset.star);
      render();
      return;
    }
  });

  app.addEventListener("change", e => {
    if (e.target.id === "fb-category") state.feedbackCategory = e.target.value;
  });

  app.addEventListener("submit", async e => {
    e.preventDefault();

    if (e.target.id === "book-form") {
      const name = document.getElementById("book-name").value;
      try {
        const res = await postJson("/api/sessions", {
          tableNumber: state.selectedTable,
          customerName: name
        });
        saveSeat({
          sessionId: res.session.sessionId,
          customerId: res.customer.customerId,
          customerName: res.customer.name,
          tableNumber: res.session.tableNumber,
          joinCode: res.session.joinCode
        });
        state.session = res.session;
        state.feedbackSubmitted = false;
        state.error = "";
        render();
        toast(`Table ${res.session.tableNumber} booked! Code: ${res.session.joinCode}`);
      } catch (err) {
        state.error = err.message;
        await refreshTables();
      }
      return;
    }

    if (e.target.id === "join-form") {
      const joinCode = document.getElementById("join-code").value;
      const customerName = document.getElementById("join-name").value;
      try {
        const res = await postJson("/api/sessions/join", { joinCode, customerName });
        saveSeat({
          sessionId: res.session.sessionId,
          customerId: res.customer.customerId,
          customerName: res.customer.name,
          tableNumber: res.session.tableNumber,
          joinCode: res.session.joinCode
        });
        state.session = res.session;
        state.feedbackSubmitted = false;
        state.error = "";
        if (window.location.search) window.history.replaceState({}, "", "/");
        render();
        toast(`Joined Table ${res.session.tableNumber}`);
      } catch (err) {
        state.error = err.message;
        render();
      }
      return;
    }

    if (e.target.id === "feedback-form") {
      const comment = document.getElementById("fb-comment").value;
      const category = document.getElementById("fb-category").value;
      const errEl = document.getElementById("fb-error");
      try {
        await postJson("/api/feedback", {
          sessionId: state.session.sessionId,
          tableNumber: state.session.tableNumber,
          customerName: state.seat.customerName,
          rating: state.feedbackRating,
          category,
          comment
        });
        state.feedbackSubmitted = true;
        await refreshReviews();
        render();
        toast("Thank you for your feedback");
      } catch (err) {
        errEl.textContent = err.message;
      }
    }
  });

  document.getElementById("close-qr").addEventListener("click", () => qrDialog.close());
  document.getElementById("close-checkout").addEventListener("click", () => checkoutDialog.close());

  document.getElementById("copy-join-link").addEventListener("click", async () => {
    const url = document.getElementById("qr-url-text").textContent;
    try {
      await navigator.clipboard.writeText(url);
      toast("Invite link copied");
    } catch {
      toast("Share code: " + (state.session ? state.session.joinCode : ""));
    }
  });

  checkoutDialog.addEventListener("click", async e => {
    const payBtn = e.target.closest("[data-paymode]");
    if (payBtn) {
      state.payMode = payBtn.dataset.paymode;
      renderCheckoutDialog();
      return;
    }

    if (e.target.id === "confirm-pay-btn") {
      const btn = e.target;
      const errEl = document.getElementById("checkout-error");
      btn.disabled = true;
      btn.textContent = "Processing settlement…";
      try {
        const res = await postJson("/api/sessions/checkout", {
          sessionId: state.seat.sessionId,
          mode: state.payMode
        });
        state.session = res.session;
        state.receiptNote = res.receiptNote || "";
        checkoutDialog.close();
        render();
        toast(`Table ${res.session.tableNumber} settled & released`);
      } catch (err) {
        errEl.textContent = err.message;
        btn.disabled = false;
        btn.textContent = "Confirm Payment & Release Table";
      }
    }
  });

  async function init() {
    const params = new URLSearchParams(window.location.search);
    const joinParam = params.get("join");
    if (joinParam) {
      saveSeat(null);
      state.pageView = "tables";
      state.entryMode = "join";
      state.joinPrefill = joinParam.trim().toUpperCase();
    }

    try {
      await Promise.all([refreshMenu(), refreshTables(), refreshReviews()]);
      if (state.seat) await refreshCurrentSession(false);
      else render();
    } catch (err) {
      app.innerHTML = `<div class="page"><div class="card"><p class="error-msg">${esc(err.message)}</p></div></div>`;
    }
  }

  init();
})();
