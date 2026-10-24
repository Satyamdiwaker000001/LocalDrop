package com.localdrop.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.widget.RemoteViews
import com.localdrop.R

// Quick Send widget: starts the send workflow from the home screen.
// Tap deep-links straight into the Send Files flow. The widget performs no
// networking and fakes no availability: the subtitle reflects the
// authoritative peer count (0 peers says so plainly; the app itself shows
// the Nearby Devices state).
class QuickSendWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context, manager: AppWidgetManager, ids: IntArray
    ) {
        val snapshot = TransferWidgetProvider.LastSnapshot.get(context)
        val now = System.currentTimeMillis()
        val display = TransferWidgetSnapshot.displayable(snapshot, now)
        val peerCount = display?.peers?.size ?: 0
        for (widgetId in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_send)
            views.setTextViewText(R.id.widget_title, "Send files")
            views.setTextViewText(R.id.widget_body, WidgetContent.quickSendSubtitle(peerCount))
            WidgetTaps.setQuickSend(context, views)
            manager.updateAppWidget(widgetId, views)
        }
    }
}
