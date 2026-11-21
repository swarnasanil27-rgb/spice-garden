"use strict";

const state = { menu: [], cart: new Map(), category: "ALL", activeOrder: null, poll: null };
const money = value => new Intl.NumberFormat("en-IN", {
  style: "currency", currency: "INR", minimumFractionDigits: 2
}).format(value);
const escapeHtml = text => String(text).replace(/[&<>"']/g, c =>
  ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

async function api(url, options) {
  const response = await fetch(url, options);
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || "Request failed.");
  return result;
}

function categoryLabel(category) {
  return category.toLowerCase().replaceAll("_", " ").replace(/\b\w/g, c => c.toUpperCase());
}

function dishImage(item) {
  const images = {
    STARTER: "https://images.unsplash.com/photo-1567188040759-fb8a883dc6d8?auto=format&fit=crop&w=900&q=85",
    MAIN_COURSE: "https://images.unsplash.com/photo-1563379926898-05f4575a45d8?auto=format&fit=crop&w=900&q=85",
    DESSERT: "https://images.unsplash.com/photo-1666190094769-b1ea9c3ee9b9?auto=format&fit=crop&w=900&q=85",
    DRINK: "https://images.unsplash.com/photo-1576092768241-dec231879fc3?auto=format&fit=crop&w=900&q=85"
  };
  return images[item.category] || images.MAIN_COURSE;
}

async function loadMenu() {
  const grid = document.getElementById("menu-grid");
  try {
    state.menu = await api("/api/menu");
    renderFilters();
    renderMenu();
  } catch (error) {
    grid.innerHTML = `<p class="notice">${escapeHtml(error.message)} <button id="retry-menu">Try again</button></p>`;
    document.getElementById("retry-menu").addEventListener("click", loadMenu);
  }
}

function renderFilters() {
  const categories = ["ALL", ...new Set(state.menu.map(item => item.category))];
  document.getElementById("filters").innerHTML = categories.map(category => `
    <button class="filter${category === state.category ? " active" : ""}" type="button" data-category="${escapeHtml(category)}">
      ${category === "ALL" ? "All dishes" : escapeHtml(categoryLabel(category))}
    </button>`).join("");
}

function renderMenu() {
  const items = state.menu.filter(item => state.category === "ALL" || item.category === state.category);
  document.getElementById("menu-grid").innerHTML = items.length ? items.map(item => `
    <article class="menu-card">
      <div class="card-image" style="background-image:url('${dishImage(item)}')">
        <span class="dish-category">${escapeHtml(categoryLabel(item.category))}</span>
      </div>
      <div class="card-body">
        <div class="card-top"><h3>${escapeHtml(item.name)}</h3><span class="price">${money(item.price)}</span></div>
        <p class="item-meta"><span class="${item.type === "VEG" ? "veg" : "nonveg"}">● ${item.type === "VEG" ? "Veg" : "Non-veg"}</span><span>Freshly prepared</span></p>
      </div>
      <button class="add-button" type="button" data-add="${item.id}">Add to order <span>+</span></button>
    </article>`).join("") : '<p class="notice">No dishes in this category.</p>';
}

function bill() {
  const subtotal = [...state.cart.entries()].reduce((sum, [id, quantity]) => {
    const item = state.menu.find(entry => entry.id === id);
    return sum + (item ? item.price * quantity : 0);
  }, 0);
  const total = Math.round(subtotal * 1.05 * 100) / 100;
  return { subtotal, gst: total - subtotal, total };
}

function renderCart() {
  const lines = [...state.cart.entries()].map(([id, quantity]) => ({
    item: state.menu.find(entry => entry.id === id), quantity
  })).filter(line => line.item);
  document.getElementById("cart-count").textContent = lines.reduce((sum, line) => sum + line.quantity, 0);
  document.getElementById("empty-cart").hidden = lines.length > 0;
  document.getElementById("cart-items").innerHTML = lines.map(({ item, quantity }) => `
    <div class="cart-line">
      <span class="cart-name">${escapeHtml(item.name)}</span><span class="cart-line-price">${money(item.price * quantity)}</span>
      <div class="quantity">
        <button type="button" data-action="minus" data-id="${item.id}" aria-label="Decrease ${escapeHtml(item.name)}">−</button>
        <span>${quantity}</span>
        <button type="button" data-action="plus" data-id="${item.id}" aria-label="Increase ${escapeHtml(item.name)}">+</button>
        <button class="remove" type="button" data-action="remove" data-id="${item.id}">Remove</button>
      </div>
    </div>`).join("");
  const totals = bill();
  document.getElementById("subtotal").textContent = money(totals.subtotal);
  document.getElementById("gst").textContent = money(totals.gst);
  document.getElementById("total").textContent = money(totals.total);
}

function openCart() {
  document.getElementById("cart-panel").classList.add("open");
  document.getElementById("overlay").hidden = false;
  document.body.style.overflow = "hidden";
}

function closeCart() {
  document.getElementById("cart-panel").classList.remove("open");
  document.getElementById("overlay").hidden = true;
  document.body.style.overflow = "";
}

function showConfirmation(order) {
  state.activeOrder = order.id;
  document.getElementById("confirmation-details").textContent =
    `Order #${order.id} for ${order.customerName}, table ${order.tableNumber}. Total ${money(order.total)} · ${order.paymentMethod}.`;
  updateStatus(order.status);
  document.getElementById("confirmation").showModal();
  clearInterval(state.poll);
  state.poll = setInterval(checkStatus, 2500);
}

function updateStatus(status) {
  const ready = status === "READY";
  document.getElementById("confirmation-status").innerHTML =
    `<span class="status ${ready ? "ready" : ""}">${ready ? "✓ Ready for you" : "● Preparing your order"}</span>`;
}

async function checkStatus() {
  if (!state.activeOrder) return;
  try {
    const order = await api(`/api/orders/${state.activeOrder}`);
    updateStatus(order.status);
    if (order.status === "READY") clearInterval(state.poll);
    await loadHistory();
  } catch {
    clearInterval(state.poll);
  }
}

async function loadHistory() {
  const body = document.getElementById("order-history");
  try {
    const orders = await api("/api/orders");
    body.innerHTML = orders.length ? orders.map(order => `
      <tr>
        <td class="order-id">#${order.id}</td><td>${escapeHtml(order.customerName)}</td>
        <td>${order.tableNumber}</td><td>${money(order.total)}</td><td>${escapeHtml(order.paymentMethod)}</td>
        <td><span class="status ${order.status === "READY" ? "ready" : ""}">${order.status === "READY" ? "Ready" : "Preparing"}</span></td>
      </tr>`).join("") : '<tr><td class="notice" colspan="6">No orders yet.</td></tr>';
  } catch (error) {
    body.innerHTML = `<tr><td class="notice" colspan="6">${escapeHtml(error.message)}</td></tr>`;
  }
}

document.getElementById("filters").addEventListener("click", event => {
  const button = event.target.closest("[data-category]");
  if (!button) return;
  state.category = button.dataset.category;
  renderFilters();
  renderMenu();
});
document.getElementById("menu-grid").addEventListener("click", event => {
  const button = event.target.closest("[data-add]");
  if (!button) return;
  const id = Number(button.dataset.add);
  state.cart.set(id, (state.cart.get(id) || 0) + 1);
  renderCart();
});
document.getElementById("cart-items").addEventListener("click", event => {
  const button = event.target.closest("[data-action]");
  if (!button) return;
  const id = Number(button.dataset.id);
  const quantity = state.cart.get(id);
  if (button.dataset.action === "remove" || (button.dataset.action === "minus" && quantity <= 1)) state.cart.delete(id);
  else state.cart.set(id, quantity + (button.dataset.action === "plus" ? 1 : -1));
  renderCart();
});
document.getElementById("cart-link").addEventListener("click", openCart);
document.getElementById("close-cart").addEventListener("click", closeCart);
document.getElementById("overlay").addEventListener("click", closeCart);
document.getElementById("refresh-orders").addEventListener("click", loadHistory);

document.getElementById("checkout").addEventListener("submit", async event => {
  event.preventDefault();
  const error = document.getElementById("checkout-error");
  const button = document.getElementById("submit-order");
  error.textContent = "";
  error.classList.remove("visible");
  if (!state.cart.size) {
    error.textContent = "Add at least one item to your order.";
    error.classList.add("visible");
    return;
  }
  const formElement = event.currentTarget;
  const form = new URLSearchParams(new FormData(formElement));
  form.set("items", [...state.cart.entries()].map(([id, quantity]) => `${id}:${quantity}`).join(","));
  button.disabled = true;
  button.textContent = "Sending order…";
  try {
    const order = await api("/api/orders", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8" },
      body: form.toString()
    });
    state.cart.clear();
    renderCart();
    formElement.reset();
    closeCart();
    showConfirmation(order);
    await loadHistory();
  } catch (e) {
    error.textContent = e.message;
    error.classList.add("visible");
  } finally {
    button.disabled = false;
    button.innerHTML = "Place order <span>→</span>";
  }
});
document.getElementById("close-confirmation").addEventListener("click", () => document.getElementById("confirmation").close());
document.getElementById("done").addEventListener("click", () => document.getElementById("confirmation").close());
document.getElementById("confirmation").addEventListener("close", () => {
  clearInterval(state.poll);
  state.activeOrder = null;
});

renderCart();
loadMenu();
loadHistory();
setInterval(loadHistory, 8000);
