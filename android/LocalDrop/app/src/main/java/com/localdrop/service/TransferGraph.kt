package com.localdrop.service

// M13 (bidirectional audit): service-owned transfer graph lifecycle
// (framework-free so JVM unit tests prove it without a device).
//
// LIFECYCLE CONTRACT (exact start/bind/unbind/stop semantics):
// - The graph is owned by TransferService, NOT by any Activity. Activities
//   bind/unbind only to observe snapshots; bind/unbind never starts or stops
//   engines, discovery loops, or workers.
// - The graph owns BOTH directions: incoming (control server, data engine,
//   receive discovery) AND outgoing (send discovery; individual sends run as
//   tracked service-side executions in OutgoingTransfers). UI/ViewModel
//   destruction never stops the graph and never cancels tracked transfers —
//   only the service's onDestroy (or explicit stop()) shuts components down.
// - start(): idempotent. The first call initializes components IN ORDER
//   (control server -> data engine -> receive discovery -> send discovery);
//   repeated starts while running are no-ops and MUST NOT create duplicate
//   engines, listeners, discovery loops, or worker threads (proven by
//   startCount).
// - stop(): idempotent. Stops in REVERSE order, aborting live transfers
//   cooperatively. Repeated stops are no-ops.
// - Partial initialization rollback: if any start step throws, every step
//   that already started is stopped in reverse order and the graph returns
//   to fully-stopped (running == false); the exception propagates to the
//   caller (the service), which must fail its own start (no half-running
//   service with no notification).
// - Zero active transfers: the graph KEEPS listening (a receiver must stay
//   discoverable with nothing in flight). The service drops foreground
//   status and cancels per-transfer notifications but does NOT stop the
//   graph: idle != stopped. Explicit stop() (user action / system kill) is
//   the only path that shuts listeners down.
// - Activity recreation (rotation, config change): the Activity is NOT an
//   owner; recreation re-binds to the same service/graph instance, so no
//   engine/listener/worker is created or destroyed by it.
class TransferGraph(
    private val components: List<LifecycleComponent>,
    private val activeCount: () -> Int = { 0 }
) {
    interface LifecycleComponent {
        fun start()
        fun stop()
    }

    // Back-compat: the original receive-only wiring (control, data,
    // discovery). Production now passes four components (see TransferService).
    constructor(
        control: LifecycleComponent,
        data: LifecycleComponent,
        discovery: LifecycleComponent,
        activeCount: () -> Int = { 0 }
    ) : this(listOf(control, data, discovery), activeCount)

    private val gate = Any()
    private var running = false

    // Counts successful transitions into running (test hook proving no
    // duplicate initialization across repeated starts).
    var startCount: Int = 0
        private set

    fun isRunning(): Boolean = synchronized(gate) { running }

    fun activeTransferCount(): Int = try { activeCount() } catch (e: Exception) { 0 }

    fun start() {
        synchronized(gate) {
            if (running) return
            val started = ArrayDeque<LifecycleComponent>()
            try {
                for (c in components) {
                    c.start()
                    started.addLast(c)
                }
                running = true
                startCount++
            } catch (e: Exception) {
                while (started.isNotEmpty()) {
                    try { started.removeLast().stop() } catch (ignored: Exception) { }
                }
                throw e
            }
        }
    }

    fun stop() {
        synchronized(gate) {
            if (!running) return
            // Reverse order; each best-effort so one failing stop cannot
            // wedge the rest shut.
            for (c in components.asReversed()) {
                try { c.stop() } catch (e: Exception) { }
            }
            running = false
        }
    }
}
