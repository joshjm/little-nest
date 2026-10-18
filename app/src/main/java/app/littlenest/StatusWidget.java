package app.littlenest;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Home-screen summary: last nappy, last sleep, and the estimated next sleep and feed. */
public final class StatusWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) { NestWidgets.refresh(context); }
}
