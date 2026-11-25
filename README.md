# Spice Garden — Restaurant Table Dining, Shared Billing & Admin Console

A full-stack Java web application for restaurant table booking, multi-guest shared table billing, real-time kitchen tracking, persistent order history, and customer feedback.

---

## Key Features

### 1. Customer Portal (`/`)
- **Visual Table Floor Plan:** Movie-ticket-style interactive seating map across four zones (`Window Alcove`, `Garden Courtyard`, `Main Dining Hall`, `Corner Lounge`).
- **Live Availability Indicators:** Available tables are highlighted in **green** and occupied tables in **red**. Occupied tables cannot be double-booked (enforced in both UI and atomic SQL transactions).
- **Menu & Dietary Filters:** Filter dishes by category (`Starters`, `Main Course`, `Desserts`, `Drinks`) and dietary preference (`Veg` / `Non-Veg`) with spice levels and live item quantities.

### 2. Multi-Customer Shared Table Billing
- **Join via Code or QR Code:** Booking an available table starts a `TableSession` and generates a unique Join Code (e.g., `T4-DAAE`) along with an in-app scannable QR code (`/?join=CODE`).
- **Independent Ordering on Multiple Phones:** Friends at the same table can join from their own devices, add/remove their own items independently, and send items to the kitchen.
- **Consolidated Running Bill:** Combines all guests' orders into a single live running bill with automatic **5% GST** calculation, offering both **By Guest** and **Combined Bill** views.
- **Real-Time Synchronization:** Server-Sent Events (SSE) push live updates across every connected phone at the table and the Admin Console.

### 3. Admin Portal (`/admin`)
- **Protected Login:** Dedicated admin login screen backed by server-side session token / `HttpOnly` cookie authentication (`ADMIN_USER` / `ADMIN_PASSWORD`).
- **Live Floor & Table Monitoring:** Displays occupied and available tables, seated guest names, live ticking seating duration, and running bill totals.
- **Live Kitchen Feed:** Track incoming orders and progress statuses (`PLACED` → `PREPARING` → `READY` → `SERVED`) with instant sync to customer devices.
- **Automatic Table Release:** Completing checkout (from either portal) atomically records the final bill and releases the table back to `AVAILABLE`.

### 4. Simulated Payment & Guest Feedback
- **Three Payment Modes:** Supports **UPI**, **Card**, and **Cash** via polymorphic Java `Payment` implementations (`UpiPayment`, `CardPayment`, `CashPayment`).
- **Post-Dining Feedback:** Guests can submit 1–5 star ratings, category tags (`Overall`, `Food`, `Service`, `Ambience`), and comments, which are stored permanently and displayed in the Admin Portal.

### 5. Permanent Database & Searchable Order History
- **MySQL + Persistent Fallback:** Connects to **MySQL** when `DB_URL` / `MYSQL_URL` / `MYSQLHOST` environment variables are configured, and automatically falls back to a file-backed **H2 database in MySQL compatibility mode** (`./data/spicegarden.mv.db`) so data persists across server restarts.
- **Search & Filtering:** Search completed sessions by guest name, join code, session ID, or dish name, and filter by table number, payment mode, and date range.

---

## Java OOP Architecture

| File | Responsibility & OOP Concepts |
| --- | --- |
| `RestaurantApp.java` | Core domain hierarchy (`Person` → `Customer`, `AdminUser`; `MenuItem` → `VegItem`, `NonVegItem`; `Payment` → `CashPayment`, `UpiPayment`, `CardPayment`; `Order`, `Billable`, `KitchenThread`, `Restaurant`) + CLI app |
| `Table.java` | `Table` class encapsulating table number, zone, capacity, and synchronized `occupy()` / `release()` state transitions |
| `TableSession.java` | `TableSession` implementing `Billable`, aggregating multi-customer orders, GST calculation, seating duration, and checkout settlement |
| `Feedback.java` | `Feedback` class encapsulating validated 1–5 star ratings, category, and comments |
| `DatabaseManager.java` | `RestaurantRepository` interface (abstraction) and `JdbcRestaurantRepository` using JDBC transactions (`commit` / `rollback`) for concurrency safety |
| `SseHub.java` | Thread-safe Server-Sent Events hub synchronizing customer phones and the Admin Console |
| `Main.java` | Java 21 `HttpServer` (virtual-thread executor) serving REST APIs, SSE streams, and static portals |

---

## Running Locally

### Prerequisites
- **Java 21+** (`javac` and `java`)
- Optional: **MySQL 8+** (if omitted, persistent file-backed H2 in MySQL mode is used automatically in `./data/`)

### Compile & Start (Windows PowerShell)
```powershell
mkdir out -Force
javac -cp "lib/*" -d out *.java
java -cp "out;lib/*" Main
```

### Compile & Start (Linux / macOS)
```bash
mkdir -p out
javac -cp "lib/*" -d out *.java
java -cp "out:lib/*" Main
```

### Open in Browser
- **Customer Portal:** `http://localhost:8080/`
- **Admin Portal:** `http://localhost:8080/admin` (default credentials: `admin` / `spicegarden2026`)

---

## Environment Variables

| Variable | Default | Description |
| --- | --- | --- |
| `PORT` | `8080` | HTTP server port |
| `ADMIN_USER` | `admin` | Admin portal username |
| `ADMIN_PASSWORD` | `spicegarden2026` | Admin portal password |
| `DB_URL` / `MYSQL_URL` | *(unset)* | Optional JDBC MySQL URL (e.g. `jdbc:mysql://host:3306/spice_garden`) |
| `DB_USER` / `MYSQLUSER` | `root` | MySQL username |
| `DB_PASSWORD` / `MYSQLPASSWORD` | `""` | MySQL password |
| `MYSQLHOST`, `MYSQLPORT`, `MYSQLDATABASE` | *(unset)* | Alternative individual MySQL connection variables |
| `DATA_DIR` | `./data` | Directory for persistent local database storage when MySQL is not configured |

---

## Docker Deployment

```bash
docker build -t spice-garden .
docker run -p 8080:8080 spice-garden
```
