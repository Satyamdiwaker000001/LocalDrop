package com.localdrop.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.localdrop.MainActivity
import com.localdrop.R

// Deep-link construction for all three widgets (device-only code:
// PendingIntent creation needs a Context, so routing DECISIONS stay in
// pure WidgetContent while this object only executes them).
object WidgetTaps {
    const val EXTRA_TAB = "localdrop.tab"
    const val EXTRA_PEER_ID = "localdrop.peerId"
    const val TAB_SEND = "send"
    const val TAB_RECEIVE = "receive"

    // Base launch intent: MainActivity is singleTop, so taps re-route the
    // existing task via onNewIntent instead of stacking activities.
    fun launchIntent(context: Context, tab: String, peerId: String? = null): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = "com.localdrop.action.OPEN_$tab".uppercase()
            putExtra(EXTRA_TAB, tab)
            if (peerId != null) putExtra(EXTRA_PEER_ID, peerId)
        }

    private fun pending(
        context: Context, tab: String, peerId: String?, requestCode: Int
    ): PendingIntent = PendingIntent.getActivity(
        context, requestCode, launchIntent(context, tab, peerId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    // Transfer Status tap: open the relevant app view for the transfer.
    fun setOpenTransfer(context: Context, views: RemoteViews, entry: WidgetTransferEntry?) {
        val tab = if (entry == null) TAB_RECEIVE else WidgetContent.routeFor(entry)
        // Fixed request code per tab: taps always land on the tab view;
        // per-transfer identity lives in the rendered snapshot, not here.
        val code = if (tab == TAB_SEND) 101 else 102
        views.setOnClickPendingIntent(R.id.widget_root, pending(context, tab, null, code))
    }

    // Quick Send tap: straight into the send flow.
    fun setQuickSend(context: Context, views: RemoteViews) {
        views.setOnClickPendingIntent(
            R.id.widget_root, pending(context, TAB_SEND, null, 200))
    }

    // Nearby Devices template: per-peer fill-in intents target the send
    // flow preselected to that peer (RemoteViewsService factory supplies
    // the peer id per row; no discovery runs in the widget).
    fun setPeerTemplate(context: Context, views: RemoteViews) {
        val template = PendingIntent.getActivity(
            context, 300, Intent(context, MainActivity::class.java).apply {
                action = "com.localdrop.action.OPEN_SEND_PEER"
                putExtra(EXTRA_TAB, TAB_SEND)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        views.setPendingIntentTemplate(R.id.widget_peer_list, template)
    }
}
