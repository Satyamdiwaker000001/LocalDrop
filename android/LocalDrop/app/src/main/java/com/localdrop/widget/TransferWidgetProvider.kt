package com.localdrop.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews

// M13: transfer widget provider (thin renderer).
//
// CANONICAL-STATE RULE: the provider never owns transfer state. It renders
// the last snapshot pushed transitionally by TransferService and applies
// TransferWidgetSnapshot.displayable(): stale snapshots and post-recreation
// invocations with no live service show idle — stale active transfers are
// never presented as current. Refreshes are transition-driven (the service
// calls requestUpdate on start/progress-terminal events, throttled), never
// a periodic poll loop.
class TransferWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context, manager: AppWidgetManager, ids: IntArray
    ) {
        val snapshot = LastSnapshot.get(context)
        val now = System.currentTimeMillis()
        val display = TransferWidgetSnapshot.displayable(snapshot, now)
        for (widgetId in ids) {
            val views = RemoteViews(context.packageName, android.R.layout.simple_list_item_1)
            val text = if (display == null || display.transfers.isEmpty()) {
                "LocalDrop — idle"
            } else {
                display.transfers.joinToString("\n") {
                    "${it.fileName} — ${"%.1f".format(it.percent)}% (${it.state})"
                }
            }
            views.setTextViewText(android.R.id.text1, text)
            manager.updateAppWidget(widgetId, views)
        }
    }

    // Persists only the last pushed snapshot. A fresh process with no push
    // has no entry -> onUpdate shows idle (no stale resurrection).
    object LastSnapshot {
        private const val PREFS = "localdrop_widget"
        private const val KEY = "last_snapshot"

        fun put(context: Context, snapshot: TransferWidgetSnapshot) {
            // Minimal flat encoding: epoch;generation;id,file,percent,state|...
            val sb = StringBuilder()
            sb.append(snapshot.epochMs).append(';').append(snapshot.generation).append(';')
            snapshot.transfers.joinTo(sb, "|") {
                "${it.transferId},${it.fileName},${it.percent},${it.state}"
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, sb.toString()).apply()
        }

        fun get(context: Context): TransferWidgetSnapshot? {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null) ?: return null
            return try { parse(raw) } catch (e: Exception) { null }
        }

        // Parses the flat encoding; fileName must not contain ',' or '|' —
        // names violating that are rejected (snapshot treated as absent).
        internal fun parse(raw: String): TransferWidgetSnapshot? {
            val head = raw.split(';', limit = 3)
            if (head.size != 3) return null
            val epoch = head[0].toLongOrNull() ?: return null
            val gen = head[1].toLongOrNull() ?: return null
            val tail = head[2]
            if (tail.isEmpty()) return TransferWidgetSnapshot(epoch, gen, emptyList())
            val entries = tail.split('|').map { part ->
                val f = part.split(',', limit = 4)
                if (f.size != 4) return null
                if (f[1].contains(',') || f[1].contains('|') || f[1].contains(';')) return null
                WidgetTransferEntry(f[0], f[1], f[2].toDoubleOrNull() ?: return null, f[3])
            }
            return TransferWidgetSnapshot(epoch, gen, entries)
        }

        fun clear(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY).apply()
        }
    }
}
