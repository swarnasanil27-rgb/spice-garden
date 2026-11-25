"use strict";

(function () {
  const TOKEN_KEY = "sg_admin_token";

  const state = {
    admin: null,
    token: sessionStorage.getItem(TOKEN_KEY) || "",
    tab: "floor", // "floor" | "kitchen" | "history" | "feedback"
    dashboard: null,
    history: [],
    feedback: [],
    feedbackMinRating: "",
    historyFilters: { q: "", table: "", mode: "ALL", status: "ALL", from: "", to: "" },
    loginError: "",
    sse: null,
    durationTimer: null
  };

  const app = document.getElementById("admin-app");
  const livePill = document.getElementById("admin-live-pill");
  const liveLabel = document.getElementById("admin-live-label");
  const logoutBtn = document.getElementById("logout-btn");
  const detailDialog = document.getElementById("admin-detail-dialog");

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

  function formatDateTime(iso) {
    if (!iso) return "—";
    try {
      return new Date(iso).toLocaleString("en-IN", {
        dateStyle: "medium",
        timeStyle: "short"
      });
    } catch {
      return iso;
    }
  }

  function toast(msg) {
    const el = document.getElementById("toast");
    el.textContent = msg;
    el.classList.add("show");
    clearTimeout(toast._t);
    toast._t = setTimeout(() => el.classList.remove("show"), 2200);
  }

  async function api(url, options = {}) {
    const headers = Object.assign({}, options.headers || {});
    if (state.token) {
      headers["Authorization"] = "Bearer " + state.token;
    }
    const res = await fetch(url, Object.assign({}, options, { headers }));
    const data = await res.json().catch(() => ({}));
    if (res.status === 401 && url !== "/api/admin/login") {
      state.admin = null;
      state.token = "";
      sessionStorage.removeItem(TOKEN_KEY);
      render();
      throw new Error("Please sign in to access the Admin Console.");
    }
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

  function setLive(online, text) {
    livePill.classList.toggle("online", online);
    liveLabel.textContent = text;
  }

  function connectAdminSse() {
    if (state.sse) {
      state.sse.close();
      state.sse = null;
    }
    if (!state.token) return;
    const es = new EventSource(`/api/admin/events?token=${encodeURIComponent(state.token)}`);
    state.sse = es;

    es.addEventListener("connected", () => setLive(true, "Live updates"));
    es.addEventListener("update", () => {
      setLive(true, "Live updates");
      refreshActiveData();
    });
    es.onerror = () => setLive(false, "Reconnecting");
  }

  async function loadDashboard() {
    state.dashboard = await api("/api/admin/dashboard");
  }

  async function loadHistory() {
    const f = state.historyFilters;
    const q = new URLSearchParams();
    if (f.q) q.set("q", f.q);
    if (f.table) q.set("table", f.table);
    if (f.mode && f.mode !== "ALL") q.set("mode", f.mode);
    if (f.status && f.status !== "ALL") q.set("status", f.status);
    if (f.from) q.set("from", f.from);
    if (f.to) q.set("to", f.to);
    state.history = await api(`/api/admin/history?${q.toString()}`);
  }

  async function loadFeedback() {
    const q = state.feedbackMinRating ? `?minRating=${encodeURIComponent(state.feedbackMinRating)}` : "";
    state.feedback = await api(`/api/admin/feedback${q}`);
  }

  async function refreshActiveData() {
    if (!state.admin) return;
    try {
      await loadDashboard();
      if (state.tab === "history") await loadHistory();
      if (state.tab === "feedback") await loadFeedback();
      render();
    } catch {
      // handled in api()
    }
  }

  function startDurationClock() {
    clearInterval(state.durationTimer);
    state.durationTimer = setInterval(() => {
      if (!state.dashboard) return;
      for (const t of state.dashboard.tables) {
        if (!t.activeSession) continue;
        const el = document.getElementById(`admin-dur-${t.tableNumber}`);
        if (!el) continue;
        const started = Date.parse(t.activeSession.startedAt);
        if (!Number.isNaN(started)) {
          el.textContent = formatDuration(Math.floor((Date.now() - started) / 1000));
        }
      }
    }, 1000);
  }

  // ==================== RENDERING ====================

  function renderLogin() {
    logoutBtn.hidden = true;
    setLive(false, "Locked");
    app.innerHTML = `
      <div class="login-shell card">
        <p class="eyebrow">Spice Garden Operations</p>
        <h1 style="font-size:28px;margin-bottom:6px;">Admin Sign In</h1>
        <p class="muted" style="font-size:13px;margin:0 0 18px;">Sign in to manage live dining tables, kitchen status, order history, and guest feedback.</p>
        <form id="admin-login-form" class="field" style="gap:14px;">
          <div class="field">
            <label for="adm-user">Username</label>
            <input id="adm-user" type="text" autocomplete="username" required placeholder="admin">
          </div>
          <div class="field">
            <label for="adm-pass">Password</label>
            <input id="adm-pass" type="password" autocomplete="current-password" required placeholder="••••••••">
          </div>
          <button class="btn btn-primary btn-block" type="submit">Sign In to Console</button>
          <p class="error-msg">${esc(state.loginError)}</p>
        </form>
        <p class="muted" style="margin:14px 0 0;padding-top:12px;border-top:1px solid var(--line);font-size:12px;">
          Default credentials: <span class="mono">admin</span> / <span class="mono">spicegarden2026</span>
        </p>
      </div>
    `;
  }

  function renderFloorTab(tables) {
    return `
      <div class="admin-tables-grid">
        ${tables.map(t => {
          const s = t.activeSession;
          const occ = t.status === "OCCUPIED" && s;
          if (!occ) {
            return `
              <article class="admin-table-card available">
                <div style="display:flex;justify-content:space-between;align-items:center;">
                  <strong style="font-family:var(--serif);font-size:20px;color:var(--avail-ink);">${esc(t.label)}</strong>
                  <span class="table-badge" style="color:var(--avail-ink);border-color:var(--avail-border);">Available</span>
                </div>
                <div class="muted" style="font-size:12px;">${esc(t.zoneLabel)} · ${t.capacity} seats</div>
                <div style="font-size:13px;color:var(--avail-ink);margin-top:auto;">Ready for walk-in or booking</div>
              </article>
            `;
          }

          const guests = s.customers.map(c => esc(c.name)).join(", ");
          return `
            <article class="admin-table-card occupied">
              <div style="display:flex;justify-content:space-between;align-items:center;">
                <div>
                  <strong style="font-family:var(--serif);font-size:20px;color:var(--occ-ink);">${esc(t.label)}</strong>
                  <span class="mono muted" style="font-size:11px;margin-left:6px;">${esc(s.joinCode)}</span>
                </div>
                <span class="table-badge" style="color:var(--occ-ink);border-color:var(--occ-border);">Occupied</span>
              </div>

              <div style="font-size:12px;" class="muted">${esc(t.zoneLabel)} · ${s.customers.length} guest(s)</div>

              <div style="font-size:13px;">
                <strong>Guests:</strong> ${guests || "—"}
              </div>

              <div style="display:flex;justify-content:space-between;padding:10px;background:var(--surface-alt);border-radius:6px;font-size:13px;">
                <div>
                  <div class="muted" style="font-size:10px;text-transform:uppercase;font-weight:700;">Duration</div>
                  <strong id="admin-dur-${t.tableNumber}" class="num">${formatDuration(s.durationSeconds)}</strong>
                </div>
                <div style="text-align:right;">
                  <div class="muted" style="font-size:10px;text-transform:uppercase;font-weight:700;">Running Bill</div>
                  <strong class="num" style="color:var(--forest);font-size:15px;">${money(s.total)}</strong>
                </div>
              </div>

              <div style="display:flex;gap:8px;margin-top:auto;">
                <button type="button" class="btn btn-outline btn-sm" style="flex:1;" data-inspect="${t.tableNumber}">Inspect Bill</button>
                <button type="button" class="btn btn-danger btn-sm" data-release="${t.tableNumber}">Checkout &amp; Release</button>
              </div>
            </article>
          `;
        }).join("")}
      </div>
    `;
  }

  function renderKitchenTab(tables) {
    const activeOrders = [];
    for (const t of tables) {
      if (!t.activeSession) continue;
      for (const o of t.activeSession.orders) {
        if (o.status !== "CANCELLED") {
          activeOrders.push({ order: o, tableNumber: t.tableNumber, joinCode: t.activeSession.joinCode });
        }
      }
    }
    activeOrders.sort((a, b) => b.order.orderId - a.order.orderId);

    if (!activeOrders.length) {
      return `<div class="card"><p class="muted" style="margin:0;">No active kitchen orders right now.</p></div>`;
    }

    return `
      <div class="data-table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>Order</th>
              <th>Table</th>
              <th>Customer</th>
              <th>Items</th>
              <th>Subtotal</th>
              <th>Status</th>
              <th>Update Kitchen</th>
            </tr>
          </thead>
          <tbody>
            ${activeOrders.map(({ order, tableNumber, joinCode }) => `
              <tr>
                <td class="mono"><strong>#${order.orderId}</strong></td>
                <td>Table ${tableNumber} <span class="mono muted" style="font-size:11px;">(${esc(joinCode)})</span></td>
                <td>${esc(order.customerName)}</td>
                <td>${order.lines.map(l => `${esc(l.name)} × ${l.quantity}`).join("<br>")}</td>
                <td class="num">${money(order.total)}</td>
                <td><span class="status-tag status-${order.status}">${order.status}</span></td>
                <td>
                  <div style="display:flex;gap:6px;flex-wrap:wrap;">
                    ${order.status === "PLACED" || order.status === "PAID"
                      ? `<button class="btn btn-outline btn-sm" data-kstatus="PREPARING" data-oid="${order.orderId}">Preparing</button>` : ""}
                    ${order.status !== "READY" && order.status !== "SERVED"
                      ? `<button class="btn btn-primary btn-sm" data-kstatus="READY" data-oid="${order.orderId}">Mark Ready</button>` : ""}
                    ${order.status === "READY"
                      ? `<button class="btn btn-brass btn-sm" data-kstatus="SERVED" data-oid="${order.orderId}">Served</button>` : ""}
                  </div>
                </td>
              </tr>
            `).join("")}
          </tbody>
        </table>
      </div>
    `;
  }

  function renderHistoryTab() {
    const f = state.historyFilters;
    return `
      <div class="card" style="margin-bottom:16px;">
        <form id="history-filter-form" class="filter-bar">
          <div class="field">
            <label for="h-q">Search guest, code, session #, dish</label>
            <input id="h-q" type="text" value="${esc(f.q)}" placeholder="e.g. Aarav, Biryani, T4-...">
          </div>
          <div class="field">
            <label for="h-table">Table</label>
            <select id="h-table">
              <option value="">All tables</option>
              ${Array.from({ length: 10 }, (_, i) => i + 1).map(n =>
                `<option value="${n}" ${String(f.table) === String(n) ? "selected" : ""}>Table ${n}</option>`
              ).join("")}
            </select>
          </div>
          <div class="field">
            <label for="h-mode">Payment</label>
            <select id="h-mode">
              ${["ALL", "UPI", "CARD", "CASH"].map(m =>
                `<option value="${m}" ${f.mode === m ? "selected" : ""}>${m === "ALL" ? "All modes" : m}</option>`
              ).join("")}
            </select>
          </div>
          <div class="field">
            <label for="h-from">From date</label>
            <input id="h-from" type="date" value="${esc(f.from)}">
          </div>
          <div class="field">
            <label for="h-to">To date</label>
            <input id="h-to" type="date" value="${esc(f.to)}">
          </div>
          <button class="btn btn-primary" type="submit">Filter</button>
        </form>
      </div>

      <div class="data-table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>Session</th>
              <th>Table</th>
              <th>Guests</th>
              <th>Bill Total</th>
              <th>Payment</th>
              <th>Duration</th>
              <th>Status</th>
              <th>Feedback</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            ${state.history.length ? state.history.map(({ session, feedback }) => {
              const fb = feedback && feedback.length ? feedback[0] : null;
              return `
                <tr>
                  <td>
                    <strong class="mono">#${session.sessionId}</strong><br>
                    <span class="muted" style="font-size:11px;">${formatDateTime(session.startedAt)}</span>
                  </td>
                  <td>Table ${session.tableNumber}<br><span class="mono muted" style="font-size:11px;">${esc(session.joinCode)}</span></td>
                  <td>${session.customers.map(c => esc(c.name)).join(", ") || "—"}</td>
                  <td class="num"><strong>${money(session.total)}</strong></td>
                  <td>${esc(session.paymentMode)}</td>
                  <td class="num">${formatDuration(session.durationSeconds)}</td>
                  <td><span class="status-tag status-${session.status}">${session.status}</span></td>
                  <td>${fb ? `${fb.rating}★` : '<span class="muted">—</span>'}</td>
                  <td><button type="button" class="btn btn-outline btn-sm" data-history-detail="${session.sessionId}">Details</button></td>
                </tr>
              `;
            }).join("") : `<tr><td colspan="9" class="muted" style="text-align:center;padding:24px;">No matching sessions found.</td></tr>`}
          </tbody>
        </table>
      </div>
    `;
  }

  function renderFeedbackTab() {
    return `
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:14px;flex-wrap:wrap;gap:10px;">
        <div class="filter-pills">
          ${[["", "All ratings"], ["5", "5 ★ only"], ["4", "4 ★ & up"], ["3", "3 ★ & up"]].map(([val, lbl]) => `
            <button type="button" class="pill ${state.feedbackMinRating === val ? "active" : ""}" data-fbfilter="${val}">${lbl}</button>
          `).join("")}
        </div>
        <span class="muted" style="font-size:13px;">${state.feedback.length} submitted review(s)</span>
      </div>

      ${state.feedback.length ? `
        <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:14px;">
          ${state.feedback.map(fb => `
            <article class="card">
              <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;">
                <span style="color:var(--brass);font-size:16px;font-weight:700;">${"★".repeat(fb.rating)}${"☆".repeat(5 - fb.rating)}</span>
                <span class="status-tag status-PLACED">${esc(fb.category)}</span>
              </div>
              <p style="margin:0 0 10px;font-size:14px;">${fb.comment ? esc(fb.comment) : '<span class="muted">No written comment.</span>'}</p>
              <div class="muted" style="font-size:12px;border-top:1px solid var(--line);padding-top:8px;">
                <strong>${esc(fb.customerName)}</strong> · Table ${fb.tableNumber} · ${formatDateTime(fb.createdAt)}
              </div>
            </article>
          `).join("")}
        </div>
      ` : `<div class="card"><p class="muted" style="margin:0;">No guest feedback submitted yet.</p></div>`}
    `;
  }

  function render() {
    if (!state.admin) {
      renderLogin();
      return;
    }

    logoutBtn.hidden = false;
    const d = state.dashboard;
    if (!d) {
      app.innerHTML = `<p class="muted">Loading dashboard…</p>`;
      return;
    }

    const s = d.stats;
    let bodyHtml = "";
    if (state.tab === "floor") bodyHtml = renderFloorTab(d.tables);
    else if (state.tab === "kitchen") bodyHtml = renderKitchenTab(d.tables);
    else if (state.tab === "history") bodyHtml = renderHistoryTab();
    else if (state.tab === "feedback") bodyHtml = renderFeedbackTab();

    app.innerHTML = `
      <div style="display:flex;justify-content:space-between;align-items:flex-end;flex-wrap:wrap;gap:12px;margin-bottom:18px;">
        <div>
          <p class="eyebrow">Operations Dashboard</p>
          <h1 style="font-size:32px;">Spice Garden Management</h1>
        </div>
        <span class="mono muted" style="font-size:11px;padding:4px 9px;border:1px solid var(--line);border-radius:4px;background:var(--surface);">
          DB: ${esc(s.dbEngine)}
        </span>
      </div>

      <section class="kpi-grid">
        <div class="kpi-card">
          <div class="kpi-label">Occupied Tables</div>
          <div class="kpi-value num" style="color:var(--occ-ink);">${s.occupiedCount} / ${d.tables.length}</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">Available Tables</div>
          <div class="kpi-value num" style="color:var(--avail-ink);">${s.availableCount}</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">Active Running Bills</div>
          <div class="kpi-value num">${money(s.activeRevenue)}</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">Settled Revenue (${s.completedCount})</div>
          <div class="kpi-value num">${money(s.completedRevenue)}</div>
        </div>
        <div class="kpi-card">
          <div class="kpi-label">Guest Rating (${s.feedbackCount})</div>
          <div class="kpi-value num">${s.feedbackCount ? `${s.avgRating} ★` : "—"}</div>
        </div>
      </section>

      <nav class="admin-tabs">
        <button type="button" class="admin-tab ${state.tab === "floor" ? "active" : ""}" data-tab="floor">Live Floor &amp; Tables</button>
        <button type="button" class="admin-tab ${state.tab === "kitchen" ? "active" : ""}" data-tab="kitchen">Live Kitchen Feed</button>
        <button type="button" class="admin-tab ${state.tab === "history" ? "active" : ""}" data-tab="history">Order History &amp; Search</button>
        <button type="button" class="admin-tab ${state.tab === "feedback" ? "active" : ""}" data-tab="feedback">Guest Feedback</button>
      </nav>

      ${bodyHtml}
    `;

    startDurationClock();
  }

  function showSessionDetailModal(session, feedbackList) {
    document.getElementById("detail-title").textContent = `Table ${session.tableNumber} · Session #${session.sessionId}`;
    document.getElementById("detail-body").innerHTML = `
      <div style="display:flex;flex-wrap:wrap;gap:14px;margin-bottom:14px;font-size:13px;">
        <span>Join Code: <strong class="mono">${esc(session.joinCode)}</strong></span>
        <span>Status: <span class="status-tag status-${session.status}">${session.status}</span></span>
        <span>Payment: <strong>${esc(session.paymentMode)}</strong></span>
        <span>Duration: <strong class="num">${formatDuration(session.durationSeconds)}</strong></span>
      </div>

      ${session.customers.map(c => `
        <div class="customer-group">
          <div class="customer-group-head">
            <span>${esc(c.name)}</span>
            <span class="num">${money(c.subtotal)}</span>
          </div>
          ${c.orders.length ? c.orders.map(o => `
            <div class="order-block">
              <div style="display:flex;justify-content:space-between;font-size:11px;margin-bottom:4px;">
                <span class="muted">Order #${o.orderId}</span>
                <span class="status-tag status-${o.status}">${o.status}</span>
              </div>
              ${o.lines.map(l => `
                <div class="order-line">
                  <span>${esc(l.name)} × ${l.quantity}</span>
                  <span class="num">${money(l.lineTotal)}</span>
                </div>
              `).join("")}
            </div>
          `).join("") : `<p class="muted" style="margin:0;font-size:12px;">No items ordered.</p>`}
        </div>
      `).join("")}

      <div class="totals-box">
        <div class="total-row"><span>Subtotal</span><span class="num">${money(session.subtotal)}</span></div>
        <div class="total-row"><span>GST (5%)</span><span class="num">${money(session.gst)}</span></div>
        <div class="total-row grand"><span>Consolidated Total</span><span class="num">${money(session.total)}</span></div>
      </div>

      ${feedbackList && feedbackList.length ? `
        <div style="margin-top:16px;padding-top:12px;border-top:1px solid var(--line);">
          <p class="eyebrow">Submitted Feedback</p>
          ${feedbackList.map(fb => `
            <div style="font-size:13px;margin-top:6px;">
              <strong>${fb.rating}★ (${esc(fb.category)})</strong> — ${esc(fb.customerName)}: ${esc(fb.comment || "No comment")}
            </div>
          `).join("")}
        </div>
      ` : ""}
    `;
    detailDialog.showModal();
  }

  // ==================== EVENTS ====================

  app.addEventListener("submit", async e => {
    e.preventDefault();
    if (e.target.id === "admin-login-form") {
      const username = document.getElementById("adm-user").value;
      const password = document.getElementById("adm-pass").value;
      try {
        const res = await postJson("/api/admin/login", { username, password });
        state.token = res.token;
        state.admin = res;
        state.loginError = "";
        sessionStorage.setItem(TOKEN_KEY, res.token);
        connectAdminSse();
        await loadDashboard();
        render();
      } catch (err) {
        state.loginError = err.message;
        render();
      }
      return;
    }

    if (e.target.id === "history-filter-form") {
      state.historyFilters = {
        q: document.getElementById("h-q").value.trim(),
        table: document.getElementById("h-table").value,
        mode: document.getElementById("h-mode").value,
        status: "ALL",
        from: document.getElementById("h-from").value,
        to: document.getElementById("h-to").value
      };
      await loadHistory();
      render();
    }
  });

  app.addEventListener("click", async e => {
    const tabBtn = e.target.closest("[data-tab]");
    if (tabBtn) {
      state.tab = tabBtn.dataset.tab;
      if (state.tab === "history") await loadHistory();
      else if (state.tab === "feedback") await loadFeedback();
      render();
      return;
    }

    const inspectBtn = e.target.closest("[data-inspect]");
    if (inspectBtn) {
      const tableNum = Number(inspectBtn.dataset.inspect);
      const t = state.dashboard.tables.find(x => x.tableNumber === tableNum);
      if (t && t.activeSession) showSessionDetailModal(t.activeSession, []);
      return;
    }

    const releaseBtn = e.target.closest("[data-release]");
    if (releaseBtn) {
      const tableNumber = Number(releaseBtn.dataset.release);
      try {
        await postJson("/api/admin/release-table", { tableNumber, mode: "CASH" });
        await loadDashboard();
        render();
        toast(`Table ${tableNumber} checked out & released`);
      } catch (err) {
        toast(err.message);
      }
      return;
    }

    const kBtn = e.target.closest("[data-kstatus]");
    if (kBtn) {
      try {
        await postJson("/api/admin/order-status", {
          orderId: Number(kBtn.dataset.oid),
          status: kBtn.dataset.kstatus
        });
        await loadDashboard();
        render();
        toast(`Order #${kBtn.dataset.oid} marked ${kBtn.dataset.kstatus}`);
      } catch (err) {
        toast(err.message);
      }
      return;
    }

    const hDetailBtn = e.target.closest("[data-history-detail]");
    if (hDetailBtn) {
      const sid = Number(hDetailBtn.dataset.historyDetail);
      const entry = state.history.find(h => h.session.sessionId === sid);
      if (entry) showSessionDetailModal(entry.session, entry.feedback);
      return;
    }

    const fbFilterBtn = e.target.closest("[data-fbfilter]");
    if (fbFilterBtn) {
      state.feedbackMinRating = fbFilterBtn.dataset.fbfilter;
      await loadFeedback();
      render();
    }
  });

  logoutBtn.addEventListener("click", async () => {
    try { await postJson("/api/admin/logout", {}); } catch {}
    if (state.sse) { state.sse.close(); state.sse = null; }
    state.admin = null;
    state.token = "";
    sessionStorage.removeItem(TOKEN_KEY);
    render();
  });

  document.getElementById("close-detail").addEventListener("click", () => detailDialog.close());

  async function init() {
    try {
      const me = await api("/api/admin/me");
      state.admin = me;
      connectAdminSse();
      await loadDashboard();
      render();
    } catch {
      state.admin = null;
      render();
    }
  }

  init();
})();
