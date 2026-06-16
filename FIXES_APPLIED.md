# ✅ All Fixes Applied — ThreadScope Live Final Version

## Round 1 Fixes (Original Audit)
- BUG-01: catch(Exception) on ringBuffer.next() replaced with correct types
- BUG-02: lastThroughputSample/lastPublishedSnapshot/lastEventsPerSec made volatile
- BUG-03: WebSocket buffer size set via ServletServerContainerFactoryBean (256KB)
- BUG-04: const _rawChaosToggle redeclaration JS crash fixed
- BUG-05: prevCpuTimes memory leak fixed — dead thread IDs evicted
- BUG-06: session.sendMessage() wrapped in synchronized(session)
- BUG-07: WebSocket wildcard origin replaced with explicit localhost origins
- BUG-08: AiRootCauseEngine thread safety: AtomicLong, volatile, synchronized
- BUG-09: Double safepoint eliminated — findDeadlockedThreads() cached in collectThreads()
- BUG-10: disruptor.shutdown(5s) with halt() fallback
- BUG-11: subList view replaced with new ArrayList copy
- BUG-12: HM_NAMES mutation fixed — names restored after each render
- BUG-13: Double WebSocket connection guard added
- BUG-14: safepoint rate documented
- BUG-15: deadSessions reusable set — no per-tick allocation
- BUG-16: @SuppressWarnings("unchecked") on EventHandler array

## Round 2 Fixes
- NEW-01: _consumerSink volatile field prevents JIT dead-code elimination
- NEW-02: _cpuSink volatile field in both chaos worker loops
- NEW-03: spring.websocket.* invalid properties documented and removed
- NEW-04: Dead InsufficientCapacityException catch removed
- NEW-05: Producer threads interrupted before disruptor.shutdown()
- NEW-06: deadSessions reusable set (per-tick allocation eliminated)
- NEW-07: setAllowedOrigins("*") replaced with explicit localhost
- NEW-08: batchAvg real computation from consumedEvents counters
- NEW-09: Single safepoint per tick (combined dump + deadlock check)
- NEW-10: WebSocket ping keepalive every 25s (_wsPingTimer)
- NEW-11: Actuator restricted to localhost interface
- NEW-12: Resize debounce 150ms — seedParticles not called on every pixel
- NEW-13: AI history + signal DOM cache (dataset.lastKey / dataset.lastSig)
- NEW-14: Unused DoubleSummaryStatistics import removed
- NEW-15: Unused AtomicReference import removed
- NEW-16: @SuppressWarnings("unchecked") on EventHandler array
- NEW-17: @CrossOrigin restricted to localhost
- NEW-18: cssText replaced with Object.assign()
- NEW-19: beforeunload cleanup handler added
- NEW-20: Redundant spring-boot-starter-web dependency removed

## Round 3 Fixes (Final Audit)
- R-01:  Throughput formula long overflow protection
- R-02:  lastPublishLatencyNs → AtomicLong (3 consumer threads)
- R-03:  buildSlotStates() receives pre-captured counters
- R-04:  AI cold-start warmupTicks guard (6 ticks)
- R-05:  MetricWindow.isTrendingUp() O(N) single-pass
- R-06:  history.toArray() snapshot instead of iterator
- C-01:  ChaosWorkloadGenerator interrupt-before-chaosActive ordering
- C-02:  prevSampleTime → AtomicLong.getAndSet() (concurrent CPU% race)
- C-03:  @Scheduled fixedRate → fixedDelay
- C-04:  publishedEvents.sum() captured once in buildMetrics()
- P-01:  Safepoint rate documented
- P-02:  JSON pre-serialized outside session loop
- P-03:  analyse() synchronized with ReentrantLock recommendation
- W-01:  Exception class name in broadcast error log
- W-02:  Payload length guard (64 bytes max)
- W-03:  AbortController timeout on fetch(/status)
- S-01:  Origin logged in afterConnectionEstablished
- S-02:  /api/chaos rate limited (500ms)
- S-03:  Unknown WS messages silently dropped
- F-01:  healthScore null/NaN guard
- F-02:  Separate try/catch for JSON parse vs applyRealSnapshot
- F-03:  AbortController on chaos REST fallback fetch
- A-01:  Lock Contention: highBlocked mandatory signal
- A-02:  CPU Saturation: sustained mandatory signal
- A-03:  Healthy confidence capped at 75 during warmup
- X-01:  Post-spawn guard in activateChaos (shutdown race)
- X-02:  cpuSpinWorker 1ms sleep (2-core VM starvation)
- D-01:  Backend retry logic (5 retries × 2s)
- D-02:  setAllowedOriginPatterns("*") accepts file:// null origin

**Total fixes across all rounds: 50+**
