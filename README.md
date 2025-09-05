<h1 align="center">Clothes-Shop — Shop Network System</h1>

<p align="center">
  <img alt="Java" src="https://img.shields.io/badge/Java-8%2B-blue.svg">
  <img alt="Platform" src="https://img.shields.io/badge/Platform-Windows%20%7C%20IDE-green">
  <a href="https://github.com/RonGershtein/ClothesShop/actions/workflows/ci.yml">
    <img alt="Build" src="https://img.shields.io/github/actions/workflow/status/RonGershtein/ClothesShop/ci.yml?branch=main">
  </a>
  <img alt="License" src="https://img.shields.io/badge/License-MIT-lightgrey.svg">
</p>

<p align="center">
Multi-tier client–server app for a clothing shop network: branch-scoped inventory, customers & sales (with cart/one-payment checkout), and real-time inter-branch chat. CSV persistence + comprehensive logging.
</p>

---

## Table of Contents
- [Features](#features)
- [Project Structure](#project-structure)
- [Quick Start (Windows)](#quick-start-windows)
- [Running from IntelliJ IDEA](#running-from-intellij-idea)
- [Data Files (CSV)](#data-files-csv)
- [Employees & Password Policy](#employees--password-policy)
- [Sales Protocol](#sales-protocol)
- [Chat Protocol](#chat-protocol)
- [Logs & Admin Reports](#logs--admin-reports)
- [Troubleshooting](#troubleshooting)
- [Architecture Notes](#architecture-notes)
- [Authors](#authors)
- [License](#license)

---

## Features
- **Branch-scoped inventory** with live updates for employees in the same branch  
- **Customers** with types `NEW`, `RETURNING`, `VIP` via strategy classes  
  - VIP: **12%** discount; **gift shirt** when **final cart total ≥ 300** (logic in `VipCustomer`)
- **Sales**
  - Single-item sale (legacy)
  - **Multi-item cart** (`SELL_MULTI`) — one payment, per-line breakdown
  - All-or-nothing stock check before committing
- **Employees & Admin**
  - Add/list/delete employees
  - **Password policy** (min length, **special character required**, letter required)
  - SHA-256 password hashing; duplicate-login prevention
- **Real-time chat** between branches
  - Request → first accept wins; others get REQUEST_TAKEN
  - Shift Manager can **LIST_CONVS** and **JOIN**
- **Comprehensive logging**
  - system, auth, employees, customers, transactions, chat (CSV)

---

## Project Structure
```
Clothes-Shop/
├─ src/
│  ├─ client/app/              # ClientConsole & menus
│  ├─ server/app/              # StoreServer main & ChatServer main
│  ├─ server/net/              # ClientHandler (store protocol)
│  ├─ server/domain/
│  │  ├─ customers/            # Customer, CustomerType, New/Returning/Vip
│  │  ├─ employees/            # Employee, EmployeeDirectory, PasswordPolicy, AuthService
│  │  ├─ invantory/            # Product, InventoryService  (name kept as in code)
│  │  └─ sales/                # SalesService (single + multi)
│  ├─ server/shared/           # Branch enum
│  └─ server/util/             # FileDatabase, Loggers, ChatLogger
├─ data/                       # CSV-like runtime data (created if missing)
├─ logs/                       # Log files (created if missing)
├─ out/                        # Compiled .class files
├─ compile.bat                 # Compile all sources (Windows)
├─ run-server.bat              # Start StoreServer (port 5050)
├─ run-chat.bat                # Start ChatServer  (port 6060)
├─ run-client.bat              # Start ClientConsole
└─ README.md
```
> Default host: **127.0.0.1** · Ports: **StoreServer 5050**, **ChatServer 6060**

---

## Quick Start (Windows)

1) **Compile**
```bat
compile.bat
```
- Scans `src\**\*.java`, compiles to `out\`, ensures `data\` and `logs\` exist, and creates an empty `data\employees.txt` on first run.

2) **Run servers** (in separate terminals)
```bat
run-server.bat
run-chat.bat
```

3) **Run one or more clients**
```bat
run-client.bat
```

4) **Login**
- Admin: `username=admin`, `password=admin`
- Employees: create via **Admin → Add employee**

---

## Running from IntelliJ IDEA
1. Open the project folder and set **Project SDK** (Java 8+).  
2. Mark `src` as **Sources Root** (right-click → Mark Directory As).  
3. Run the following mains:
   - `server.app.StoreServer`
   - `server.app.ChatServer`
   - `client.app.ClientConsole`

---

## Data Files (CSV)
- `data/employees.txt`
  ```
  employeeId,username,hash,role,branch,accountNumber,phone,fullName,nationalId
  ```
- `data/customers.txt`
  ```
  id,fullName,phone,type
  ```
- `data/products.txt`
  ```
  sku,category,branch,quantity,price
  ```
- `data/password_policy.txt`
  ```
  minimumLength=<int>
  requireSpecial=<true|false>
  requireLetter=<true|false>
  ```

> Files are plain text; the app reads/writes them via `FileDatabase`.

---

## Employees & Password Policy
- Admin can configure policy in the client (**Admin → Set password policy**):
  - **Minimum length** (default: 6)
  - **Require special char** — at least one non-alphanumeric (e.g. `!@#$%`)
  - **Require letter** — at least one `A–Z`/`a–z`
- Passwords are hashed with **SHA-256** before being saved.
- Duplicate login is blocked at the server.

---

## Sales Protocol

### Single item (legacy)
```
SELL <branch> <sku> <qty> <customerId>
→ OK SALE <base> <discount> <final> <customerType>
```

### Multi-item cart (single payment)
```
SELL_MULTI <branch> <customerId> <sku1>:<qty1>,<sku2>:<qty2>,...
→ OK SALE_MULTI <type> <base> <discount> <final> [GIFT]
   LINE <sku> <category> <qty> <unit> <lineBase> <lineDiscount> <lineFinal>
   [GIFT_SHIRT 1]
   OK END
```
- **All-or-nothing**: stock verified for the entire cart before commit.  
- Discounts calculated by customer type (VIP = 12%).  
- Gift shirt emitted only when the type qualifies (VIP and **final ≥ 300** after discount).

---

## Chat Protocol (short)
```
HELLO <username> <role:SALESPERSON|CASHIER|SHIFT_MANAGER> <branch:HOLON|TEL_AVIV|RISHON>
REQUEST_BRANCH <branch> | REQUEST_ANY_OTHER_BRANCH | REQUEST_USER <username>
ACCEPT <requestId>
LIST_CONVS                      # Shift Manager
JOIN <conversationId>           # Shift Manager
MSG <text...> | END | QUIT
```

**Events**
```
INCOMING_REQUEST <id> <fromUser> <fromBranch>
PAIRED <convId> <user1,user2[,manager]>
REQUEST_TAKEN <id> | REQUEST_CANCELLED <id>
MANAGER_JOINED <username>
INFO LEFT_CONVERSATION | CONVERSATION_ENDED
```

---

## Logs & Admin Reports
Logs are written under `logs\`:
- `system.log` — server/runtime events  
- `auth.log` — logins/logouts, duplicate login prevention  
- `employees.log` — employee add/delete  
- `customers.log` — customer added  
- `transactions.log` — sales (single & multi)  
- `chat_messages.csv` — conversation id, user, message, timestamp

From the client, go to **Admin → Logs & Reports** to view the last *N* lines of each file.

---

## Troubleshooting
- **Compilation ok but run fails** → Run scripts from project root; ensure `out\` exists.  
- **“ERR LOGIN ALREADY_CONNECTED”** → same username already connected; logout other session.  
- **Password rejected** → check policy (length, special char, letter).  
- **SKU or customer not found** → add via menus or edit `data\*.txt`.

---

## Architecture Notes
- **StoreServer (5050)** spawns a `ClientHandler` per connection (thread-per-client).  
- **ChatServer (6060)** manages sessions, broadcast requests, accept race, manager join.  
- **Domain services** are layered by responsibility:
  - `InventoryService`, `SalesService`, `CustomerService`, `EmployeeDirectory`
- **CustomerType strategies** encapsulate discounts & gifts (`VipCustomer` implements both).
- **Persistence** uses simple CSV via `FileDatabase`.  
- **Logging** uses `server.util.Loggers` and `server.util.ChatLogger`.

---

## Authors
Dana Oshri · Lihi Kimhazi · Noa Gerbi · Ron Gershtein

---

## License
MIT — see `LICENSE`.
