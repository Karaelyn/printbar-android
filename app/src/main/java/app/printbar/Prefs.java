package app.printbar;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

final class Prefs {
    static final String URL = "url", PRINTERS = "printers", POLL = "poll", ENABLED = "enabled";
    static final String DEFAULT_URL = "http://10.0.0.221";
    static final String DEFAULT_PRINTERS = "http://10.0.0.221\nhttp://10.0.0.233";

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("printbar", Context.MODE_PRIVATE);
    }

    /** Liste des machines, une par ligne : « adresse » ou « Nom = adresse ». */
    static String printersText(Context c) {
        SharedPreferences sp = get(c);
        String t = sp.getString(PRINTERS, null);
        if (t != null) return t;
        String old = sp.getString(URL, null); // réglage de la v1.0 (une seule machine)
        return old != null && !old.trim().isEmpty() && !old.trim().equals(DEFAULT_URL) ? old.trim() : DEFAULT_PRINTERS;
    }

    static List<Cosmos> printers(Context c) {
        return parse(printersText(c));
    }

    static List<Cosmos> parse(String text) {
        List<Cosmos> list = new ArrayList<>();
        for (String raw : text.split("[\\r\\n]+")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String name = "", url = line;
            int eq = line.indexOf('=');
            if (eq > 0) { name = line.substring(0, eq).trim(); url = line.substring(eq + 1).trim(); }
            if (!url.isEmpty()) list.add(new Cosmos(name, url));
        }
        return list;
    }
}
