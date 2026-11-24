package app.printbar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/** Client de l'API CosmOS : /api/printer/stats + /api/job/ (portage du connecteur de PrintBar Windows). */
public final class Cosmos {

    public enum State { PRINTING, PAUSED, IDLE, COMPLETE, CANCELLED, ERROR, OFFLINE }

    public static final class Status {
        public State state = State.OFFLINE;
        public String file = "", message = "";
        public double progress;            // 0..1
        public int layer, totalLayers;
        public long elapsedS = -1, remainingS = -1;
        public double ext, extT, bed, bedT;
        public boolean hasTemps;
    }

    private final String base;
    private Double factor; // secondes par unité de print_time / eta (null = pas encore déduit)

    public Cosmos(String url) {
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        base = u;
    }

    public String baseUrl() { return base; }

    private JSONObject get(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setConnectTimeout(4000);
        c.setReadTimeout(4000);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code + " sur " + path);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                return new JSONObject(bo.toString("UTF-8"));
            }
        } finally {
            c.disconnect();
        }
    }

    public Status fetch() {
        Status s = new Status();
        try {
            JSONObject stats = get("/api/printer/stats");
            JSONObject last = null;
            try { last = get("/api/job/"); } catch (Exception ignored) { }

            double now = System.currentTimeMillis() / 1000.0;
            String status = str(stats, "status").toLowerCase(Locale.ROOT);
            JSONObject job = stats.optJSONObject("job");

            s.state = mapState(status);
            s.message = (s.state == State.ERROR || s.state == State.OFFLINE) ? status : "";
            s.file = str(job, "filename");
            Double prog = num(job, "progress");
            s.progress = prog != null ? clamp(prog / 100.0) : 0;
            Double l = num(job, "layer"), tl = num(job, "total_layers");
            s.layer = l != null ? l.intValue() : 0;
            s.totalLayers = tl != null ? tl.intValue() : 0;

            readTemps(stats, s);

            boolean active = s.state == State.PRINTING || s.state == State.PAUSED;
            Double start = num(last, "start");
            Double elapsedFromStart = (active && start != null && start > 0 && now - start < 7 * 86400) ? now - start : null;
            Double pt = num(job, "print_time");
            Double eta = num(job, "eta");

            // Unité de print_time / eta : déduite en comparant à l'heure de début du job
            if (factor == null && elapsedFromStart != null && elapsedFromStart > 120 && pt != null && pt > 0) {
                double e = elapsedFromStart;
                factor = Math.abs(pt * 60 - e) < Math.abs(pt - e) ? 60.0 : 1.0;
            }
            double f = factor != null ? factor : 60; // par défaut : minutes (observé sur CosmOS 1.2.0)

            if (active) {
                double el = elapsedFromStart != null ? elapsedFromStart : (pt != null ? pt : 0) * f;
                s.elapsedS = (long) el;
                if (eta != null && eta >= 0) s.remainingS = (long) (eta * f);
                else if (s.progress > 0.01 && el > 30) s.remainingS = (long) (el / s.progress - el);
            } else if (s.state == State.IDLE && last != null) {
                // Fin récente : on montre le résultat du dernier job pendant 10 min
                Double end = num(last, "end");
                String js = str(last, "status").toLowerCase(Locale.ROOT);
                if (end != null && end > 0 && now - end < 600 && now - end > -60) {
                    if (js.contains("success") || js.contains("complete") || js.contains("done")) {
                        s.state = State.COMPLETE;
                        s.progress = 1;
                    } else if (js.contains("cancel")) {
                        s.state = State.CANCELLED;
                    } else if (js.contains("fail") || js.contains("error")) {
                        s.state = State.ERROR;
                        s.message = js;
                    }
                    if (s.state != State.IDLE) {
                        s.file = str(last, "filename");
                        Double d = num(last, "duration");
                        if (d != null && d > 0) s.elapsedS = d.longValue();
                    }
                }
                if (s.state == State.IDLE) {
                    s.file = "";
                    s.layer = s.totalLayers = 0;
                    s.progress = 0;
                }
            }
        } catch (Exception e) {
            Throwable t = e;
            while (t.getCause() != null) t = t.getCause();
            Status o = new Status();
            o.state = State.OFFLINE;
            o.message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            return o;
        }
        return s;
    }

    static State mapState(String st) {
        if (st.contains("paus")) return State.PAUSED;
        if (st.contains("cancel")) return State.CANCELLED;
        if (st.contains("print") || st.contains("resum") || st.contains("start") || st.contains("busy")) return State.PRINTING;
        if (st.contains("error") || st.contains("fail")) return State.ERROR;
        if (st.contains("offline") || st.contains("disconnect") || st.contains("closed")) return State.OFFLINE;
        return State.IDLE; // operational, ready, idle, standby…
    }

    private static void readTemps(JSONObject stats, Status s) {
        JSONArray heaters = stats.optJSONArray("heaters");
        if (heaters != null) {
            boolean gotExt = false;
            for (int i = 0; i < heaters.length(); i++) {
                JSONObject h = heaters.optJSONObject(i);
                if (h == null) continue;
                String name = str(h, "name");
                Double a = num(h, "actual"), t = num(h, "target");
                double act = a != null ? a : 0, tgt = t != null ? t : 0;
                if (name.equalsIgnoreCase("Bed")) {
                    s.bed = act; s.bedT = tgt; s.hasTemps = true;
                } else if (name.toUpperCase(Locale.ROOT).startsWith("T") && name.length() <= 3) {
                    // Buse active = première buse avec une consigne, sinon T0
                    if (!gotExt || (tgt > 0 && s.extT <= 0)) {
                        s.ext = act; s.extT = tgt; gotExt = true; s.hasTemps = true;
                    }
                }
            }
            if (gotExt) return;
        }
        JSONObject t = stats.optJSONObject("temperature");
        if (t != null) {
            Double b = num(t, "bed");
            if (b != null) { s.bed = b; s.hasTemps = true; }
            JSONArray nz = t.optJSONArray("nozzles");
            if (nz != null) for (int i = 0; i < nz.length(); i++) {
                if (!nz.isNull(i)) { s.ext = nz.optDouble(i, 0); s.hasTemps = true; break; }
            }
        }
    }

    private static String str(JSONObject o, String k) {
        if (o == null || o.isNull(k)) return "";
        Object v = o.opt(k);
        return v instanceof String ? (String) v : "";
    }

    private static Double num(JSONObject o, String k) {
        if (o == null || !o.has(k) || o.isNull(k)) return null;
        Object v = o.opt(k);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    private static double clamp(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    // ---- formatage ----

    public static String dur(long sec) {
        if (sec < 0) return "–";
        long h = sec / 3600, m = (sec % 3600) / 60;
        if (h > 0) return h + " h " + String.format(Locale.FRANCE, "%02d", m);
        if (m > 0) return m + " min";
        return sec + " s";
    }

    public static String name(String file) {
        if (file == null) return "";
        String f = file;
        int i = f.lastIndexOf('/');
        if (i >= 0) f = f.substring(i + 1);
        if (f.toLowerCase(Locale.ROOT).endsWith(".gcode")) f = f.substring(0, f.length() - 6);
        return f;
    }
}
