package app.printbar;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    static final String URL = "url", POLL = "poll", ENABLED = "enabled";
    static final String DEFAULT_URL = "http://10.0.0.221";

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("printbar", Context.MODE_PRIVATE);
    }
}
