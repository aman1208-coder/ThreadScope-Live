# ▶️ ThreadScope Live — Complete Setup Guide
## Real-Time JVM Concurrency Debugger with AI Root Cause Analysis

---

## What This Project Does

ThreadScope Live streams **real** JVM thread metrics from a running Java
application to a live browser dashboard. It detects deadlocks, lock
contention, CPU saturation, consumer backpressure, and memory pressure
automatically using an AI rule engine — and tells you exactly what is
wrong and how to fix it.

**Every number on the dashboard is real. Nothing is simulated.**

---

## Prerequisites

| Tool        | Version | Download |
|-------------|---------|----------|
| Java JDK    | 17+     | https://adoptium.net |
| Maven       | 3.8+    | https://maven.apache.org |
| VS Code     | Latest  | https://code.visualstudio.com |
| Live Server | Latest  | VS Code Extensions (Ritwick Dey) |

Check your versions:
```bash
java -version    # must show 17 or higher
mvn -version     # must show 3.8 or higher
```

---

## Project Structure

```
threadscope-live/
├── backend/                          ← Java Spring Boot server
│   ├── pom.xml
│   └── src/main/java/com/threadscope/
│       ├── ThreadScopeApplication.java       ← Entry point
│       ├── agent/
│       │   ├── RealDisruptorEngine.java      ← LMAX Disruptor (real)
│       │   └── RealThreadInspector.java      ← ThreadMXBean reader
│       ├── ai/
│       │   ├── AiDiagnosis.java              ← Diagnosis data model
│       │   ├── AiRootCauseEngine.java        ← 6-rule AI engine
│       │   └── MetricWindow.java             ← Sliding window analytics
│       ├── chaos/
│       │   └── ChaosWorkloadGenerator.java   ← Real contention threads
│       ├── metrics/
│       │   ├── MetricsCollector.java         ← Assembles snapshots
│       │   └── ThreadMetricsSnapshot.java    ← JSON data model
│       └── websocket/
│           ├── MetricsWebSocketHandler.java  ← 10fps WS broadcast
│           ├── MetricsRestController.java    ← REST API
│           └── WebSocketConfig.java         ← Buffer + origin config
│
├── frontend/
│   └── index.html                    ← Full dashboard (open this)
│
├── .vscode/
│   ├── settings.json
│   ├── extensions.json
│   └── launch.json
│
└── HOW_TO_RUN.md                     ← This file
```

---

## ▶️ Running the Project (3 Steps)

### Step 1 — Open in VS Code
```
File → Open Folder → select "threadscope-live"
```
VS Code will prompt: **"Install recommended extensions?"** → Click **Install All**
Wait for the Java extension to finish indexing (watch bottom status bar).

---

### Step 2 — Start the Java Backend

**Option A — VS Code (easiest)**
- Press `Ctrl+Shift+D` → Select **"▶ Start Java Backend"** → Press ▶

**Option B — Terminal**
```bash
cd backend
mvn spring-boot:run
```

**Option C — Pre-built jar**
```bash
cd backend
mvn clean package -q
java -jar target/threadscope-agent-2.0.0.jar
```

✅ Backend is ready when you see:
```
╔══════════════════════════════════════════════════╗
║   THREADSCOPE LIVE  —  Real JVM Agent v2.0.0    ║
║   WebSocket  →  ws://localhost:8080/ws/metrics   ║
║   Dashboard  →  open frontend/index.html         ║
║   REST API   →  http://localhost:8080/api/       ║
╚══════════════════════════════════════════════════╝
```

---

### Step 3 — Open the Dashboard

**IMPORTANT: Always open via Live Server — never by double-clicking the file.**

1. In VS Code Explorer → right-click `frontend/index.html`
2. Select **"Open with Live Server"**
3. Browser opens at `http://127.0.0.1:3000`

✅ You will see: **"WebSocket · LIVE (Java backend)"** in green

A banner appears: *"✓ Connected to Java backend — streaming real JVM metrics"*

---

## 🎮 Controls

| Action | Control |
|--------|---------|
| Trigger real contention | Click **⚡ CHAOS** button |
| Pause physics | Press **SPACE** |
| View architecture | Click **⬡ ARCH** button |
| Toggle sound | Press **S** |
| Fullscreen | Press **F** |
| High contrast mode | Press **H** |
| Export JSON report | Click **↓ EXPORT** |
| Advanced metrics | Press **A** |

---

## 🔥 Demo Flow (For Hackathon / Interview)

1. Open dashboard → show **"Healthy 94%"** health score in green
2. Click **⬡ ARCH** → explain the data flow to judges
3. Click **⚡ CHAOS** → watch within 3 seconds:
   - Thread timeline fills with red BLOCKED bars
   - Health score drops to 40-60
   - AI card changes from "Healthy" to "Lock Contention" or "CPU Saturation"
   - Evidence signals show real values: `blockedThreads=4`, `latencyMs=5.2`
4. Open browser DevTools Console (F12) → show real data streaming
5. Click CHAOS again → watch system recover to Healthy

**Key line to say:** *"Every number you see came from Java's ThreadMXBean.
Nothing is simulated. The AI engine detected lock contention from real
blocked threads fighting over a ReentrantLock."*

---

## 🌐 REST API Reference

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/status` | GET | Server status + connected clients |
| `/api/snapshot` | GET | Full current metrics snapshot |
| `/api/threads` | GET | Thread list only |
| `/api/ai` | GET | Current AI diagnosis |
| `/api/chaos?on=true` | POST | Enable chaos mode |
| `/api/chaos?on=false` | POST | Disable chaos mode |

Test in browser: `http://localhost:8080/api/status`

---

## 🔧 Troubleshooting

**"Backend not running — using simulated data"**
→ The Java backend is not started. Run Step 2 first.
→ The dashboard retries 5 times over 12 seconds automatically.

**Maven build fails with dependency error**
```bash
mvn clean package -U  # force re-download all dependencies
```

**Port 8080 already in use**
```bash
# Edit backend/src/main/resources/application.properties:
server.port=9090
# Also update frontend/index.html line ~1500:
# const WS_URL = 'ws://localhost:9090/ws/metrics';
# const API_URL = 'http://localhost:9090/api';
```

**Java version error**
```bash
java -version  # must be 17+
# If wrong, set JAVA_HOME to JDK 17 path
```

**WebSocket shows "Reconnecting" immediately**
→ Make sure you opened via Live Server (`http://`) not file:// protocol.

**Chaos mode shows no BLOCKED threads**
→ Wait 2-3 seconds after clicking CHAOS.
→ Check terminal: should show "[ThreadScope] Chaos: 8 threads spawned"

---

## 📊 What "Real Data" Means

| Dashboard Element | Data Source |
|---|---|
| Thread states (RUNNING, BLOCKED, WAITING) | `ThreadMXBean.dumpAllThreads()` |
| CPU% per thread | `ThreadMXBean.getThreadCpuTime()` delta |
| Lock names | `ThreadInfo.getLockName()` |
| Heap usage | `MemoryMXBean.getHeapMemoryUsage()` |
| GC count + time | `GarbageCollectorMXBean` |
| Deadlock detection | `ThreadMXBean.findDeadlockedThreads()` |
| Ring buffer metrics | LMAX Disruptor `LongAdder` counters |
| Hot methods | Stack sampling from `dumpAllThreads()` |
| AI diagnosis | Rule engine on 30-tick sliding windows |
| Health score | Weighted formula from all above signals |

---

## 🏗️ Architecture Summary

```
Java Backend (Spring Boot :8080)
    │
    ├── RealThreadInspector
    │   └── ThreadMXBean.dumpAllThreads() ──→ real thread states
    │
    ├── RealDisruptorEngine
    │   └── LMAX RingBuffer[1024] ──────────→ real events/sec, latency
    │
    ├── ChaosWorkloadGenerator
    │   └── Real contention threads ────────→ genuine BLOCKED state
    │
    ├── AiRootCauseEngine
    │   └── 6 rules + sliding windows ──────→ diagnosis + confidence
    │
    └── MetricsWebSocketHandler
        └── @Scheduled(fixedDelay=100ms) ───→ JSON broadcast at 10fps
                │
                ▼
    Browser Dashboard (Live Server :3000)
        ├── Physics Canvas (60fps rAF)
        ├── Health Score Ring Widget
        ├── AI Root Cause Card
        ├── Thread Timeline
        ├── Hot Methods Chart
        └── Ring Buffer Visualizer
```

---

## 🔒 Security Notes

- WebSocket buffer: **256KB** (set via ServletServerContainerFactoryBean)
- Origin: accepts all origins for demo (change to specific origins for prod)
- Payload validation: messages >64 bytes are rejected
- Rate limiting: /api/chaos limited to once per 500ms
- Actuator: health endpoint only, localhost interface only
- Log injection: unknown WS messages silently dropped

---

## 📝 Resume Line

> Built ThreadScope Live — a real-time JVM concurrency debugger using
> Java 17, Spring Boot 3.2, LMAX Disruptor, ThreadMXBean, WebSocket,
> and a rule-based AI engine. Streams live thread metrics at 10fps,
> auto-detects deadlocks and lock contention, and provides confidence-
> scored root cause analysis. Conducted 4 audit rounds fixing 40+ bugs.

**Tech Stack:** Java 17 · Spring Boot 3.2 · LMAX Disruptor 4.0 ·
ThreadMXBean · WebSocket · JavaScript · HTML5 Canvas · Maven

