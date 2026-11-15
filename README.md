# QueueLess (कतार-मुक्त)
### Simple QR-Based Digital Token Management Application for Local Businesses

QueueLess is a lightweight, mobile-first digital token register designed specifically for local neighborhood businesses—such as barber shops, salons, dental/general clinics, repair shops, small restaurants, and vehicle service centers.

It solves exactly one problem: **Allow a shop owner to manage customers waiting for their turn, while customers join the queue by scanning a QR code and track their live turn on their smartphones without downloading any app.**

---

## 🌟 Key Highlights & Features

- **No App Installation / No Sign-up for Customers**: Customers simply scan the shop's printed or on-screen QR code (`/join/{publicQueueId}`), enter their name, and immediately get a digital token with a private live-tracking link (`/track/{trackingToken}`).
- **Real-Time Live Updates via Server-Sent Events (SSE)**: Both the owner's dashboard and the customer's tracking screen update instantly when tokens are called, completed, skipped, or added—without manual page refreshing.
- **Audio Chime & Visual Notifications**: Alert tones and glowing badges notify customers when their token is called or being served.
- **Customer Live Queue Push Notifications (PWA + Web Push)**: Standard VAPID-authenticated Web Push notifications. Customers can enable notifications with one tap after joining to receive live milestone updates (e.g. 5, 3, 2, 1 ahead), priority alerts when called, and turn-completion updates without keeping the browser tab open. Single-tag notification replacement prevents notification spam. Seamless iOS 16.4+ and Android PWA support.
- **Sequential Daily Tokens & Concurrency-Safe**: Tokens start at `001, 002, 003...` for each session. High concurrency thread-safe token generator prevents duplicate or skipped numbers.
- **In-Memory Speed + Full Database Persistence**: Active queue operations leverage an in-memory `ArrayList` (FIFO order) and `ConcurrentHashMap` (O(1) lookup) synchronized with PostgreSQL / JPA transactions.
- **Bilingual Interface**: Seamless 1-click toggle between **English** and **Hindi (हिंदी)** across all owner and customer screens.
- **Direct UPI Subscriptions**: Simple flat pricing (₹51 Monthly / ₹501 Yearly) with dynamic UPI QR code, copyable UPI ID, and manual UTR verification. No payment gateways or unnecessary transaction fees.
- **Zero Enterprise Clutter**: No charts, no graphs, no CRM, no complex staff hierarchies, and no microservices. Pure digital token register simplicity.

---

## 📐 Technology Stack

| Layer | Technologies |
|---|---|
| **Backend** | Java 21, Spring Boot 3.3.5, Spring Security (JWT), Spring Data JPA, Server-Sent Events (SSE), Maven |
| **Frontend** | React 18, TypeScript, Vite, Tailwind CSS, Lucide Icons, React Router 6, Canvas Confetti |
| **Database** | PostgreSQL (Production) / Embedded H2 (Local Development & Portability) |
| **In-Memory Queue** | `ArrayList` (FIFO ordered queue) + `ConcurrentHashMap` (fast lookup) with thread-safe synchronized locking |
| **Testing** | JUnit 5, Mockito, Spring Boot MockMvc, Playwright E2E Suite |

---

## 🚀 Quick Start Guide (One-Click Startup Scripts)

You can bring up the entire monolithic application (backend REST APIs + embedded frontend UI) using a single script from the project root.

### 1. Windows (PowerShell)
Open PowerShell in the project directory and run:
```powershell
.\start.ps1
```

> [!TIP]
> **PowerShell Execution Policy Tip**: If PowerShell restricts script execution on your system, run:
> ```powershell
> powershell -ExecutionPolicy Bypass -File .\start.ps1
> ```

**Optional PowerShell Flags**:
- Force re-compilation of frontend and backend:
  ```powershell
  .\start.ps1 -Build
  ```
- Start server in background without auto-opening the browser:
  ```powershell
  .\start.ps1 -NoBrowser
  ```

---

### 2. Windows (Command Prompt or Double-Click)
- **Via Command Prompt**:
  ```cmd
  start.bat
  ```
- **Via File Explorer**:
  Simply double-click `start.bat` in the project root folder.

---

### 3. macOS / Linux / Git Bash
Open your terminal in the project directory and run:
```bash
chmod +x start.sh
./start.sh
```

---

### ⚙️ What the Startup Script Does Automatically

1. **Java 21+ Verification**: Automatically confirms that a compatible Java runtime is installed and available in your `PATH`.
2. **Auto-Packaging**: Checks for `backend/target/queueless-backend-1.0.0.jar`. If missing, it builds the React frontend assets (`npm run build`), copies them to Spring Boot's static resources, and compiles the backend with Maven (`mvn clean package -DskipTests`).
3. **Port In-Use Detection**: Checks if port `8080` is already active to prevent port collision errors and displays direct access instructions.
4. **Instant Launch & Browser Opening**: Boots up the application and automatically opens [http://localhost:8080](http://localhost:8080) in your default browser.

---

### 🔑 Default Credentials & Quick Access

- **QueueLess App**: [http://localhost:8080](http://localhost:8080)
- **Demo Shop Owner Login**:
  - Email: `owner@queueless.com`
  - Password: `password123`
  - *(Or click the **1-Click Demo Login** button on the sign-in page)*
- **Admin Configuration Screen**: [http://localhost:8080/admin](http://localhost:8080/admin)
  - Master Admin Key: `admin-secret-queueless-2026`

---

### 🛠️ Developer Mode (Hot Reload)

#### 1. Start the Spring Boot Backend
```bash
cd backend
mvn spring-boot:run
```
*Backend runs on port `8080`.*

#### 2. Start the Vite React Frontend
```bash
cd frontend
npm install
npm run dev
```
*Vite dev server starts on [http://localhost:5173](http://localhost:5173) with automatic proxying to backend on port 8080.*

---

## 📱 User Flows & Screens

### 1. Shop Owner Flow
1. **Login / Register**: Enter email and password, or use the 1-click demo button.
2. **Dashboard Register**:
   - Start today's queue (session status switches to `OPEN`).
   - View summary counters: **Total Customers**, **Waiting**, and **Currently Serving**.
   - Focus on the main customer table with token numbers, joining time, and status pills.
3. **Queue Controls**:
   - **Add Customer Manually**: Name (required) and phone number (optional).
   - **Show QR Code**: Displays high-resolution QR poster with shop name, direct link, and PNG download / print options.
   - **Call Next**: Moves top waiting customer to `CALLED`.
   - **Start Serving**: Marks customer as `SERVING`.
   - **Complete**: Finishes service and marks `COMPLETED`. Current serving is not forcefully overwritten when calling next.
   - **Skip / Recall**: Moves customer to `SKIPPED`, with option to recall them back to queue.
   - **Mark No-Show**: Closes customer as `NO_SHOW`.
   - **Edit Customer**: Update customer name or phone number.
   - **Remove Customer**: Removes customer from active queue with `CANCELLED` status.
   - **Close Queue**: Closes today's queue session.

### 2. Customer QR Joining & Live Tracking
1. **Join Queue (`/join/{publicQueueId}`)**:
   - Customer scans QR code placed at shop entrance or counter.
   - Page opens on mobile browser (clean white background, large readable purple buttons).
   - Enters name (e.g., "Rahul Sharma") and optional mobile number.
   - Clicks **Get My Token**.
2. **Live Tracking (`/track/{trackingToken}`)**:
   - Large bold token badge (e.g., `005`).
   - Shows currently serving token and exact count of **People Ahead**.
   - Shows approximate waiting time (e.g., `~20 mins`) with disclaimer note.
   - Real-time SSE updates: live sound chime triggers when called or serving.
   - **Leave Queue** button allows voluntary departure (status updates to `LEFT`).
   - Optional 1-click **Rejoin Queue** option if left accidentally.

### 3. Subscription & Admin Settings
- **Shop Owner Subscription Page (`/subscription`)**:
  - Two transparent plans: **Monthly (₹51 for 30 days)** and **Yearly (₹501 for 365 days)**.
  - Displays dynamic UPI QR code (`upi://pay?pa=...`) and configurable UPI ID.
  - Instructions to pay via Google Pay, PhonePe, Paytm, or BHIM.
  - Owner submits the 12-digit UPI UTR reference for manual verification.
- **Admin Configuration Screen (`/admin`)**:
  - Authenticated via secure Master Admin Key.
  - Update platform UPI ID and subscription pricing anytime.
  - View all registered shops, inspect pending UTR submissions, and activate/extend subscriptions.

---

## 🧪 Automated Testing

### 1. Backend Unit & Concurrency Tests
Run the comprehensive JUnit 5 and Mockito test suite:
```bash
cd backend
mvn test
```
**Test Coverage Includes**:
- `ActiveQueueConcurrencyTest.java`: 120 parallel threads concurrently claiming tokens to verify strictly sequential token ordering (`001` through `120`) without duplicate tokens or race conditions.
- `QueueServiceTest.java`: Complete queue session lifecycle (creation, manual addition, QR join, call next, serving, complete, skip, edit, remove, wait calculation, queue closure).
- `QueueControllerIntegrationTest.java`: End-to-end MockMvc testing for auth, public joining, tracking, subscription UTR submission, and admin activation.

### 2. Frontend Playwright E2E Tests
Run the Playwright end-to-end tests:
```bash
cd frontend
npx playwright test
```
**E2E Scenarios Covered**:
1. Owner Demo Login & Dashboard Navigation
2. Starting Queue, Adding Customer Manually, and Showing QR Modal
3. English and Hindi (हिंदी) Bilingual Switching
4. Subscription Page with UPI Payment Details & Copy UPI ID
5. Shop Profile & Service Duration Settings
6. Full Customer QR Scan, Token Issuance, Live Tracking, and Queue Leaving

---

## 🔒 Security & Data Integrity

- **Password Hashing**: BCrypt encryption for owner passwords.
- **Stateless Authentication**: Signed JWT tokens with role-based endpoint protection.
- **Shop-Level Isolation**: Owners can only view and modify queue data belonging to their authenticated shop ID.
- **Unpredictable Public Identifiers**: QR codes and customer tracking URLs use cryptographically secure UUID tokens rather than sequential database IDs, preventing enumeration attacks.
- **Concurrency Safety**: Active queue token counters and in-memory lists are synchronized to guarantee monotonic token issuance even under simultaneous customer requests.
- **Web Push VAPID Security**: Push subscriptions are strictly bound to the customer's unique `trackingToken`. Notification payloads are encrypted with ECDSA P-256 (VAPID) and authenticated. Auto-generated ephemeral keys for local development or configurable via environment variables (`APP_WEBPUSH_VAPID_PUBLIC_KEY`, `APP_WEBPUSH_VAPID_PRIVATE_KEY`, `APP_WEBPUSH_VAPID_SUBJECT`).
- **Completed Token 15-Minute Expiry Policy**: Completed tokens allow private tracking for 15 minutes post-completion, after which public access expires with HTTP 410 Gone. Completed tokens can never be reactivated.

---

## 📂 Project Structure

```
QUEUELESS/
├── backend/
│   ├── src/main/java/com/queueless/
│   │   ├── config/              # SecurityConfig, WebMvcConfig, JwtAuthenticationFilter
│   │   ├── controller/          # Auth, Queue, PublicQueue, Subscription, Shop, Admin
│   │   ├── dto/                 # Request/Response payloads
│   │   ├── entity/              # User, Shop, QueueSession, QueueCustomer, Subscription, Setting
│   │   ├── model/               # In-memory ShopActiveQueue (ArrayList + ConcurrentHashMap)
│   │   ├── repository/          # Spring Data JPA repositories
│   │   ├── security/            # JwtTokenProvider, CustomUserDetailsService
│   │   └── service/             # QueueService, ActiveQueueManager, SseEmitterService, AuthService...
│   └── src/test/java/           # JUnit 5, Concurrency & MockMvc tests
├── frontend/
│   ├── src/
│   │   ├── api/                 # Typed API client with Axios
│   │   ├── components/          # Navbar, Modals (Add, Edit, QR, Confirm), StatusBadge
│   │   ├── context/             # AuthContext, LanguageContext (Bilingual EN/HI)
│   │   ├── locales/             # Translations dictionary (English + Hindi)
│   │   ├── pages/               # Login, Dashboard, CustomerJoin, CustomerTrack, Subscription, Settings, Admin
│   │   └── types/               # TypeScript interfaces
│   └── e2e/                     # Playwright E2E test suite
└── README.md
```

---

## 📄 License
This project is open-source and built for local small businesses.
