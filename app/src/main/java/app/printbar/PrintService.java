package app.printbar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.service.quicksettings.TileService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Service de premier plan : interroge chaque machine et tient à jour une notification unique regroupant toutes les machines. */
public class PrintService extends Service {

    public static final String ACTION_STOP = "app.printbar.STOP";
    static final String CH_PROGRESS = "progress";
    static final String CH_ALERTS = "alerts";
    static final int ID_BASE = 100;   // notification unique (toutes les machines)

    /** Une machine surveillée et son dernier état connu (lu par l'activité et la tuile). */
    public static final class Watch {
        public final int index;
        public final Cosmos client;
        public volatile Cosmos.Status last;
        public volatile long lastAt;
        Cosmos.State prev;
        int offlineCount;
        HandlerThread thread;
        Handler handler;

        Watch(int index, Cosmos client) { this.index = index; this.client = client; }
    }

    public static volatile List<Watch> watches = new ArrayList<>();
    public static volatile boolean running;

    private NotificationManager nm;
    private PowerManager pm;
    private PowerManager.WakeLock wake;
    private int pollSec;

    public static void start(Context c) {
        c.startForegroundService(new Intent(c, PrintService.class));
    }

    public static void stop(Context c) {
        c.startService(new Intent(c, PrintService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = getSystemService(NotificationManager.class);
        pm = getSystemService(PowerManager.class);
        createChannels(this);
    }

    static void createChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel p = new NotificationChannel(CH_PROGRESS, "Progression", NotificationManager.IMPORTANCE_LOW);
        p.setDescription("Barre d'avancement de chaque machine");
        p.setShowBadge(false);
        nm.createNotificationChannel(p);
        NotificationChannel a = new NotificationChannel(CH_ALERTS, "Alertes", NotificationManager.IMPORTANCE_HIGH);
        a.setDescription("Fin d'impression, pause, erreur, machine injoignable");
        nm.createNotificationChannel(a);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        pollSec = Math.max(2, Prefs.get(this).getInt(Prefs.POLL, 5));
        List<Cosmos> clients = Prefs.printers(this);
        if (clients.isEmpty()) clients.add(new Cosmos(Prefs.DEFAULT_URL));

        // (Re)démarrage : on arrête les anciennes boucles (et les notifications par machine de la v1.1)
        stopWatches();
        for (int i = 1; i < 20; i++) nm.cancel(ID_BASE + i);

        List<Watch> list = new ArrayList<>();
        for (int i = 0; i < clients.size(); i++) list.add(new Watch(i, clients.get(i)));
        watches = list;

        Notification first = buildAll();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_BASE, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ID_BASE, first);
        }
        running = true;
        Prefs.get(this).edit().putBoolean(Prefs.ENABLED, true).apply();
        refreshTile();

        for (Watch w : list) {
            w.thread = new HandlerThread("printbar-" + w.index);
            w.thread.start();
            w.handler = new Handler(w.thread.getLooper());
            w.handler.post(() -> poll(w));
        }
        return START_STICKY;
    }

    private void stopWatches() {
        for (Watch w : watches) {
            if (w.handler != null) w.handler.removeCallbacksAndMessages(null);
            if (w.thread != null) w.thread.quitSafely();
        }
    }

    private void shutdown() {
        running = false;
        Prefs.get(this).edit().putBoolean(Prefs.ENABLED, false).apply();
        stopWatches();
        nm.cancel(ID_BASE);
        releaseWake();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        refreshTile();
    }

    private void refreshTile() {
        try { TileService.requestListeningState(this, new ComponentName(this, PrintTile.class)); } catch (Exception ignored) { }
    }

    private void poll(Watch w) {
        if (!running || !watches.contains(w)) return;
        Cosmos.Status s = w.client.fetch();
        w.lastAt = System.currentTimeMillis();

        // Une erreur réseau isolée ne fait pas basculer en "injoignable" pendant une impression
        if (s.state == Cosmos.State.OFFLINE) w.offlineCount++; else w.offlineCount = 0;
        boolean wasActive = w.prev == Cosmos.State.PRINTING || w.prev == Cosmos.State.PAUSED;
        if (s.state == Cosmos.State.OFFLINE && wasActive && w.offlineCount < 3) {
            schedule(w, true);
            return;
        }
        w.last = s;
        if (!running || !watches.contains(w)) return;

        alertOnTransition(w, w.prev, s);
        postAll();
        w.prev = s.state;
        updateWake();
        refreshTile();
        schedule(w, s.active());
    }

    private void schedule(Watch w, boolean active) {
        // Écran éteint : on espace les requêtes (la notification n'est pas visible)
        int sec = pm.isInteractive() ? pollSec : (active ? 30 : 60);
        w.handler.postDelayed(() -> poll(w), sec * 1000L);
    }

    private synchronized void updateWake() {
        boolean any = false;
        for (Watch w : watches) if (w.last != null && w.last.active()) any = true;
        if (any) {
            if (wake == null) {
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "printbar:poll");
                wake.setReferenceCounted(false);
            }
            if (!wake.isHeld()) wake.acquire(26 * 3600 * 1000L);
        } else {
            releaseWake();
        }
    }

    private synchronized void releaseWake() {
        if (wake != null && wake.isHeld()) wake.release();
    }

    private void alertOnTransition(Watch w, Cosmos.State prev, Cosmos.Status s) {
        if (prev == null || prev == s.state) return;
        boolean wasActive = prev == Cosmos.State.PRINTING || prev == Cosmos.State.PAUSED;
        String title = null, text = Cosmos.name(s.file);
        switch (s.state) {
            case COMPLETE:
                title = "Impression terminée ✓";
                if (s.elapsedS > 0) text += " · " + Cosmos.dur(s.elapsedS);
                break;
            case PAUSED: if (prev == Cosmos.State.PRINTING) title = "Impression en pause"; break;
            case CANCELLED: if (wasActive) title = "Impression annulée"; break;
            case ERROR: title = "Erreur"; text = s.message; break;
            case OFFLINE: if (wasActive) { title = "Machine injoignable"; text = s.message; } break;
            case IDLE: if (wasActive) title = "Impression arrêtée"; break;
            default: break;
        }
        if (title == null) return;
        // Tout reste dans la notification unique : on la republie une fois sur le canal « Alertes » (son + bandeau)
        alertText = w.client.label() + " — " + title + (text == null || text.isEmpty() ? "" : " · " + text);
        alertPending = true;
    }


    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private boolean postScheduled;
    private volatile boolean alertPending;
    private volatile String alertText;

    /** Regroupe les mises à jour des machines en une seule publication (300 ms). */
    private void postAll() {
        synchronized (this) {
            if (postScheduled) return;
            postScheduled = true;
        }
        main.postDelayed(() -> {
            synchronized (PrintService.this) { postScheduled = false; }
            if (!running) return;
            boolean alert = alertPending;
            alertPending = false;
            nm.notify(ID_BASE, buildAll(alert));
        }, 300);
    }

    private Notification buildAll() { return buildAll(false); }

    private Notification buildAll(boolean alert) {
        List<Watch> ws = watches;
        Notification.Builder b = new Notification.Builder(this, alert ? CH_ALERTS : CH_PROGRESS)
                .setSmallIcon(R.drawable.ic_printer)
                .setOngoing(true)
                .setOnlyAlertOnce(!alert)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setCustomContentView(Notif.bar(this, ws))
                .setCustomBigContentView(Notif.cards(this, ws))
                .setContentTitle("PrintBar")
                .setSubText(Notif.summary(ws))
                .setTicker(alert ? alertText : null)
                .setContentIntent(PendingIntent.getActivity(this, 3,
                        new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE));
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, PrintService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        b.addAction(new Notification.Action.Builder(null, "Arrêter le suivi", stop).build());
        return b.build();
    }

    static String temps(Cosmos.Status s) {
        if (!s.hasTemps) return "";
        StringBuilder t = new StringBuilder("Buse ").append(Math.round(s.ext));
        if (s.extT > 0) t.append("/").append(Math.round(s.extT));
        t.append(" °C · Plateau ").append(Math.round(s.bed));
        if (s.bedT > 0) t.append("/").append(Math.round(s.bedT));
        t.append(" °C");
        if (s.chamber != null) t.append(" · Caisson ").append(Math.round(s.chamber)).append(" °C");
        return t.toString();
    }

    /** « SuperNova · SN 1011-00033 · logiciel 2.2.0 » */
    static String ident(Cosmos.Status s) {
        List<String> p = new ArrayList<>();
        if (!s.model.isEmpty()) p.add(s.model);
        if (!s.serial.isEmpty()) p.add("SN " + s.serial);
        if (!s.firmware.isEmpty()) p.add("logiciel " + s.firmware);
        return String.join(" · ", p);
    }

    private static String endTime(long remainingS) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        int today = c.get(java.util.Calendar.DAY_OF_YEAR);
        c.add(java.util.Calendar.SECOND, (int) remainingS);
        String hm = String.format(Locale.FRANCE, "%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
        return c.get(java.util.Calendar.DAY_OF_YEAR) == today ? hm : "demain " + hm;
    }

    @Override
    public void onDestroy() {
        running = false;
        stopWatches();
        releaseWake();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
