package com.localdrop.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.localdrop.R

// Transfer Status widget (thin renderer; one of three M13 widgets).
//
// CANONICAL-STATE RULE: the provider never owns transfer state. It renders
// the last snapshot pushed transitionally by TransferService and applies
// TransferWidgetSnapshot.displayable(): stale snapshots and post-recreation
// invocations with no live service show idle/recent — stale active
// transfers are never presented as current. Refreshes are
// transition-driven, never a periodic poll loop.
class TransferWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context, manager: AppWidgetManager, ids: IntArray
    ) {
        val snapshot = LastSnapshot.get(context)
        val now = System.currentTimeMillis()
        val display = TransferWidgetSnapshot.displayable(snapshot, now)
        for (widgetId in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_transfer_status)
            if (display == null || display.transfers.isEmpty()) {
                val idle = if (display?.recent.isNullOrEmpty()) "LocalDrop — idle"
                else display!!.recent
                views.setTextViewText(R.id.widget_title, "LocalDrop")
                views.setTextViewText(R.id.widget_body, idle)
            } else {
                views.setTextViewText(R.id.widget_title, "LocalDrop — transferring")
                views.setTextViewText(
                    R.id.widget_body,
                    display.transfers.joinToString("\n", transform = WidgetContent::statusLine))
            }
            WidgetTaps.setOpenTransfer(context, views, display?.transfers?.firstOrNull())
            manager.updateAppWidget(widgetId, views)
        }
    }

    // Persists only the last pushed snapshot. A fresh process with no push
    // has no entry -> onUpdate shows idle (no stale resurrection).
    object LastSnapshot {
        private const val PREFS = "localdrop_widget"
        private const val KEY = "last_snapshot"

        fun put(context: Context, snapshot: TransferWidgetSnapshot) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, encode(snapshot)).apply()
        }

        fun get(context: Context): TransferWidgetSnapshot? {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, null) ?: return null
            return try { parse(raw) } catch (e: Exception) { null }
        }

        // Flat encoding, sectioned so old snapshots stay parseable:
        // epoch;generation;transferEntries;peerEntries;recentEscaped
        // transfer entry: id,file,percent,state[,direction,transferred,total,speed]
        //   (4 fields = pre-direction snapshots; 8 = current; else reject)
        // peer entry: id,name,type,status
        // Names must not contain ',', '|', ';' — violating snapshots are
        // rejected (fail-closed, treated as absent), same as M13.
        internal fun encode(snapshot: TransferWidgetSnapshot): String {
            val sb = StringBuilder()
            sb.append(snapshot.epochMs).append(';').append(snapshot.generation).append(';')
            snapshot.transfers.joinTo(sb, "|") {
                "${it.transferId},${it.fileName},${it.percent},${it.state}," +
                    "${it.direction},${it.transferredBytes},${it.totalBytes},${it.speedText}"
            }
            sb.append(';')
            snapshot.peers.joinTo(sb, "|") {
                "${it.deviceId},${it.displayName},${it.deviceType},${it.status}"
            }
            sb.append(';').append(escape(snapshot.recent))
            return sb.toString()
        }

        internal fun parse(raw: String): TransferWidgetSnapshot? {
            val head = raw.split(';', limit = 5)
            if (head.size < 3) return null
            val epoch = head[0].toLongOrNull() ?: return null
            val gen = head[1].toLongOrNull() ?: return null
            val transfers = if (head.size > 2) parseTransfers(head[2]) ?: return null else emptyList()
            val peers = if (head.size > 3) parsePeers(head[3]) ?: return null else emptyList()
            val recent = if (head.size > 4) unescape(head[4]) ?: return null else ""
            return TransferWidgetSnapshot(epoch, gen, transfers, peers, recent)
        }

        private fun parseTransfers(section: String): List<WidgetTransferEntry>? {
            if (section.isEmpty()) return emptyList()
            return section.split('|').map { part ->
                val f = part.split(',')
                if (f.size != 4 && f.size != 8) return null
                if (f[1].contains(',') || f[1].contains('|') || f[1].contains(';')) return null
                WidgetTransferEntry(
                    f[0], f[1], f[2].toDoubleOrNull() ?: return null, f[3],
                    direction = if (f.size == 8) f[4] else "",
                    transferredBytes = if (f.size == 8) f[5].toLongOrNull() ?: return null else -1L,
                    totalBytes = if (f.size == 8) f[6].toLongOrNull() ?: return null else -1L,
                    speedText = if (f.size == 8) f[7] else "")
            }
        }

        private fun parsePeers(section: String): List<WidgetPeer>? {
            if (section.isEmpty()) return emptyList()
            return section.split('|').map { part ->
                val f = part.split(',', limit = 4)
                if (f.size != 4) return null
                if (f[1].contains(',') || f[1].contains('|') || f[1].contains(';')) return null
                WidgetPeer(f[0], f[1], f[2], f[3])
            }
        }

        private fun escape(s: String): String =
            s.replace("\\", "\\\\").replace(";", "\\;")

        private fun unescape(s: String): String? {
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\') {
                    if (i + 1 >= s.length) return null
                    val n = s[i + 1]
                    if (n != '\\' && n != ';') return null
                    out.append(n)
                    i += 2
                } else {
                    out.append(c)
                    i++
                }
            }
            return out.toString()
        }

        fun clear(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY).apply()
        }
    }
}
