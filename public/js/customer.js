"use strict";

(function () {
  const STORAGE_KEY = "sg_customer_seat";
  const DISH_IMAGES = {
    "Paneer Tikka": "/public/dishes/paneer-tikka.svg",
    "Chicken Wings": "/public/dishes/chicken-wings.svg",
    "Veg Biryani": "/public/dishes/veg-biryani.svg",
    "Butter Chicken": "/public/dishes/butter-chicken.svg",
    "Gulab Jamun": "/public/dishes/gulab-jamun.svg",
    "Masala Chai": "/public/dishes/masala-chai.svg"
  };

  const state = {
    menu: [],
    tables: [],
    seat: loadSeat(), // { sessionId, customerId, customerName, tableNumber, joinCode }
    session: null,
    entryMode: "book", // "book" | "join"
    selectedTable: null,
    joinPrefill: "",
    category: "ALL",
    dietFilter: "ALL",
    billTab: "by-guest", // "by-guest" | "combined"
    picks: {},
    payMode: "UPI",
    receiptNote: "",
    feedbackSubmitted: false,
    feedbackRating: 5,
    feedbackCategory: "Overall",
    error: "",
    sse: null,
    sseChannel: null,
    durationTimer: null
  };

  const app = document.getElementById("app");
  const livePill = document.getElementById("live-pill");
  const liveLabel = document.getElementById("live-label");
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
    if (seat) {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(seat));
    } else {
      sessionStorage.removeItem(STORAGE_KEY);
    }
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

  function toast(msg) {
    const el = document.getElementById("toast");
    el.textContent = msg;
    el.classList.add("show");
    clearTimeout(toast._t);
    toast._t = setTimeout(() => el.classList.remove("show"), 2200);
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

    es.addEventListener("connected", () => {
      setLiveStatus(true, "Live sync");
    });

    es.addEventListener("update", () => {
      setLiveStatus(true, "Live sync");
      if (state.seat) {
        refreshCurrentSession(true);
      } else {
        refreshTables();
      }
    });

    es.onerror = () => {
      setLiveStatus(false, "Reconnecting");
    };
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
    if (!state.seat) render();
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
        const elapsed = Math.floor((Date.now() - started) / 1000);
        clock.textContent = formatDuration(elapsed);
      }
    }, 1000);
  }

  // ==================== RENDERING: SEATING VIEW ====================

  function renderSeatingView() {
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

    const floorHtml = zones.map(zone => `
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
                <div class="table-tile-top">
                  <span class="table-num">${esc(t.label)}</span>
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
    `).join("");

    const rightPanel = state.entryMode === "book" ? `
      <div class="card">
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
            <input id="book-name" type="text" maxlength="80" required placeholder="e.g. Swarna">
          </div>
          <button class="btn btn-primary btn-block" type="submit" ${state.selectedTable ? "" : "disabled"}>
            ${state.selectedTable ? `Book Table ${state.selectedTable} & Start Ordering` : "Select an available table first"}
          </button>
          <p class="error-msg" id="entry-error">${esc(state.error)}</p>
        </form>
      </div>
    ` : `
      <div class="card">
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
      <section class="seating-intro">
        <div>
          <p class="eyebrow">Welcome to Spice Garden</p>
          <h1>Choose your table to dine</h1>
          <p class="muted" style="margin:6px 0 0;">
            ${availCount} available · ${occCount} occupied · Multiple guests can share one table bill in real time
          </p>
        </div>
        <div class="mode-tabs" role="tablist">
          <button class="mode-tab ${state.entryMode === "book" ? "active" : ""}" type="button" data-entry="book">Book a Table</button>
          <button class="mode-tab ${state.entryMode === "join" ? "active" : ""}" type="button" data-entry="join">Join with Code / QR</button>
        </div>
      </section>

      <div class="seating-grid">
        <section class="card">
          <div class="floor-header">
            <div>
              <h2 style="font-size:22px;">Dining Floor Plan</h2>
              <p class="muted" style="margin:2px 0 0;font-size:13px;">Live table availability updates automatically</p>
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
    `;
  }

  // ==================== RENDERING: COMPLETED RECEIPT + FEEDBACK ====================

  function renderCompletedView(session) {
    const fbHtml = state.feedbackSubmitted ? `
      <div class="card" style="background:var(--avail-bg);border-color:var(--avail-border);">
        <p class="eyebrow" style="color:var(--avail-ink);">Feedback Saved</p>
        <h3 style="color:var(--avail-ink);">Thank you for dining with us</h3>
        <p style="margin:8px 0 14px;font-size:14px;">Your ${state.feedbackRating}★ rating and feedback have been shared with the Spice Garden team.</p>
        <button class="btn btn-primary" type="button" id="new-visit-btn">Return to Floor Plan</button>
      </div>
    ` : `
      <div class="card">
        <p class="eyebrow">Share Your Experience</p>
        <h3>How was your meal?</h3>
        <p class="muted" style="font-size:13px;margin:6px 0 14px;">Rate your experience at Table ${session.tableNumber}. Feedback appears immediately on our Admin Console.</p>
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
            <button class="btn btn-outline" type="button" id="new-visit-btn">Skip & Return to Floor Plan</button>
          </div>
        </form>
      </div>
    `;

    app.innerHTML = `
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
    `;
  }

  // ==================== RENDERING: ACTIVE ORDERING WORKSPACE ====================

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

    const filteredMenu = state.menu.filter(item => {
      if (state.category !== "ALL" && item.category !== state.category) return false;
      if (state.dietFilter !== "ALL" && item.type !== state.dietFilter) return false;
      return true;
    });

    const myCustomerId = state.seat.customerId;
    const myOrders = (session.customers.find(c => c.customerId === myCustomerId)?.orders || []);
    const hasMyPlacedOrder = myOrders.some(o => o.status === "PLACED" && o.lines.length > 0);

    const byGuestHtml = session.customers.map(c => {
      const isMe = c.customerId === myCustomerId;
      const ordersHtml = c.orders.length ? c.orders.map(o => `
        <div class="order-block">
          <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:4px;">
            <span class="muted" style="font-size:11px;">Order #${o.orderId}</span>
            <span class="status-tag status-${o.status}">${o.status}</span>
          </div>
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
            <span>${esc(c.name)} ${isMe ? "(You)" : ""}</span>
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
      <section class="session-banner">
        <div>
          <p class="eyebrow" style="color:#f3d28f;margin-bottom:4px;">Active Shared Table Session</p>
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
          <div class="menu-controls">
            <div class="filter-pills">
              ${categories.map(cat => `
                <button type="button" class="pill ${state.category === cat ? "active" : ""}" data-cat="${cat}">
                  ${catLabels[cat]}
                </button>
              `).join("")}
            </div>
            <div class="filter-pills">
              <button type="button" class="pill ${state.dietFilter === "ALL" ? "active" : ""}" data-diet="ALL">All</button>
              <button type="button" class="pill ${state.dietFilter === "VEG" ? "active" : ""}" data-diet="VEG">Veg</button>
              <button type="button" class="pill ${state.dietFilter === "NON_VEG" ? "active" : ""}" data-diet="NON_VEG">Non-Veg</button>
            </div>
          </div>

          <div class="menu-grid">
            ${filteredMenu.map(item => {
              const qty = state.picks[item.id] || 1;
              const isVeg = item.type === "VEG";
              const img = DISH_IMAGES[item.name] || "/public/dishes/veg-biryani.svg";
              return `
                <article class="dish-card">
                  <div class="dish-head">
                    <img class="dish-img" src="${img}" alt="${esc(item.name)}">
                    <div>
                      <div class="dish-title-row">
                        <span class="diet-dot ${isVeg ? "veg" : "nonveg"}" title="${isVeg ? "Vegetarian" : "Non-Vegetarian"}"></span>
                        <h3>${esc(item.name)}</h3>
                      </div>
                      <div class="dish-sub">
                        ${catLabels[item.category] || item.category}
                        ${!isVeg && item.spice ? ` · Spice ${item.spice}/5` : ""}
                      </div>
                    </div>
                  </div>
                  <div class="dish-foot">
                    <span class="dish-price num">${money(item.price)}</span>
                    <div style="display:flex;align-items:center;gap:8px;">
                      <div class="stepper">
                        <button type="button" data-dec="${item.id}" aria-label="Decrease">−</button>
                        <span>${qty}</span>
                        <button type="button" data-inc="${item.id}" aria-label="Increase">+</button>
                      </div>
                      <button type="button" class="btn btn-primary btn-sm" data-add="${item.id}">Add</button>
                    </div>
                  </div>
                </article>
              `;
            }).join("")}
          </div>
        </section>

        <aside class="card bill-panel">
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
    `;
  }

  function render() {
    if (!state.seat || !state.session) {
      clearInterval(state.durationTimer);
      renderSeatingView();
    } else {
      renderSessionView(state.session);
      startDurationClock();
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

  function renderCheckoutDialog() {
    const s = state.session;
    if (!s) return;
    document.getElementById("checkout-table-label").textContent = `Table ${s.tableNumber}`;
    document.getElementById("checkout-error").textContent = "";

    const simNote = {
      UPI: `Simulated UPI transfer of <strong>${money(s.total)}</strong> to <code>spicegarden@upi</code>.`,
      CARD: `Simulated POS card swipe / tap for <strong>${money(s.total)}</strong>.`,
      CASH: `Simulated cash payment of <strong>${money(s.total)}</strong> collected at the counter.`
    }[state.payMode];

    document.getElementById("checkout-summary").innerHTML = `
      <div style="border:1px solid var(--line);border-radius:6px;padding:12px;margin-bottom:12px;max-height:180px;overflow-y:auto;">
        ${s.consolidatedLines.map(l => `
          <div class="order-line">
            <span>${esc(l.name)} × ${l.quantity}</span>
            <span class="num">${money(l.lineTotal)}</span>
          </div>
        `).join("")}
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
      <div class="pay-sim-box">${simNote}</div>
      <button type="button" class="btn btn-primary btn-block" id="confirm-pay-btn">Confirm Payment &amp; Release Table</button>
    `;
  }

  // ==================== EVENT HANDLERS ====================

  app.addEventListener("click", async e => {
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
        toast("Added to shared table bill");
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

    if (e.target.id === "open-checkout-btn") {
      renderCheckoutDialog();
      checkoutDialog.showModal();
      return;
    }

    if (e.target.id === "leave-view-btn" || e.target.id === "new-visit-btn") {
      saveSeat(null);
      state.session = null;
      state.receiptNote = "";
      state.feedbackSubmitted = false;
      state.error = "";
      await refreshTables();
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
    if (e.target.id === "fb-category") {
      state.feedbackCategory = e.target.value;
    }
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
        toast(`Table ${res.session.tableNumber} booked! Share code ${res.session.joinCode}`);
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
        if (window.location.search) {
          window.history.replaceState({}, "", "/");
        }
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
      state.entryMode = "join";
      state.joinPrefill = joinParam.trim().toUpperCase();
    }

    try {
      await Promise.all([refreshMenu(), refreshTables()]);
      if (state.seat) {
        await refreshCurrentSession(false);
      } else {
        render();
      }
    } catch (err) {
      app.innerHTML = `<div class="card"><p class="error-msg">${esc(err.message)}</p></div>`;
    }
  }

  init();
})();
