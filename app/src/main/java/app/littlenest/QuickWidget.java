package app.littlenest;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Home-screen buttons: wet nappy, poo nappy, start or end sleep. */
public final class QuickWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) { NestWidgets.refresh(context); }
}
