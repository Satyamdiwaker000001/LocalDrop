package com.localdrop.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.localdrop.R

// Nearby Devices widget: exposes currently discovered peers from the
// canonical discovery snapshot. Tap a device opens the send flow targeted
// to that device (peer preselected). The widget maintains NO discovery
// engine; without a fresh snapshot it shows an accurate empty state.
class NearbyDevicesWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context, manager: AppWidgetManager, ids: IntArray
    ) {
        val snapshot = TransferWidgetProvider.LastSnapshot.get(context)
        val now = System.currentTimeMillis()
        val display = TransferWidgetSnapshot.displayable(snapshot, now)
        for (widgetId in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_nearby_devices)
            val peers = display?.peers ?: emptyList()
            if (peers.isEmpty()) {
                views.setTextViewText(R.id.widget_title, "Nearby devices")
                views.setTextViewText(
                    R.id.widget_body,
                    if (display == null) "Open LocalDrop to scan" else "No nearby devices")
                views.setViewVisibility(R.id.widget_peer_list, android.view.View.GONE)
                views.setViewVisibility(R.id.widget_body, android.view.View.VISIBLE)
            } else {
                views.setTextViewText(
                    R.id.widget_title, "Nearby devices (${peers.size})")
                views.setViewVisibility(R.id.widget_body, android.view.View.GONE)
                views.setViewVisibility(R.id.widget_peer_list, android.view.View.VISIBLE)
                val svc = Intent(context, WidgetPeerService::class.java)
                views.setRemoteAdapter(R.id.widget_peer_list, svc)
                views.setEmptyView(R.id.widget_peer_list, R.id.widget_body)
            }
            WidgetTaps.setPeerTemplate(context, views)
            manager.updateAppWidget(widgetId, views)
            manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_peer_list)
        }
    }
}

// Collection bridge: serves the persisted peer rows to the widget host.
// Runs in the app process on widget-host request only (no loop, no
// discovery, no networking); reads the same canonical snapshot file.
class WidgetPeerService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        WidgetPeerFactory(applicationContext)
}

private class WidgetPeerFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {
    private var peers: List<WidgetPeer> = emptyList()

    override fun onCreate() {}
    override fun onDestroy() {}

    override fun onDataSetChanged() {
        val snapshot = TransferWidgetProvider.LastSnapshot.get(context)
        val display = TransferWidgetSnapshot.displayable(snapshot, System.currentTimeMillis())
        peers = display?.peers ?: emptyList()
    }

    override fun getCount(): Int = peers.size

    override fun getViewAt(position: Int): RemoteViews? {
        if (position < 0 || position >= peers.size) return null
        val peer = peers[position]
        return RemoteViews(context.packageName, R.layout.widget_peer_row).apply {
            setTextViewText(R.id.widget_peer_name, WidgetContent.peerLine(peer))
            // Fill-in: the template PendingIntent opens the send flow; the
            // peer id rides along so the app preselects this device.
            setOnClickFillInIntent(
                R.id.widget_peer_row,
                Intent().apply { putExtra(WidgetTaps.EXTRA_PEER_ID, peer.deviceId) })
        }
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false
}
