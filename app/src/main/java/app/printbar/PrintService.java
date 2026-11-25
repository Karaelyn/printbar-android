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
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.service.quicksettings.TileService;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Service de premier plan : interroge chaque machine et tient à jour une notification de progression par machine. */
public class PrintService extends Service {

    public static final String ACTION_STOP = "app.printbar.STOP";
    static final String CH_PROGRESS = "progress";
    static final String CH_ALERTS = "alerts";
    static final int ID_BASE = 100;   // notification de progression de la machine i : 100 + i
    static final int ID_ALERT = 200;  // alerte de la machine i : 200 + i

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

        // (Re)démarrage : on arrête les anciennes boucles et on efface les notifications en trop
        stopWatches();
        for (int i = clients.size(); i < 20; i++) nm.cancel(ID_BASE + i);

        List<Watch> list = new ArrayList<>();
        for (int i = 0; i < clients.size(); i++) list.add(new Watch(i, clients.get(i)));
        watches = list;

        Notification first = build(list.get(0), null);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_BASE, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ID_BASE, first);
        }
        running = true;
        Prefs.get(this).edit().putBoolean(Prefs.ENABLED, true).apply();
        refreshTile();

        for (Watch w : list) {
            if (w.index > 0) nm.notify(ID_BASE + w.index, build(w, null));
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
        for (Watch w : watches) nm.cancel(ID_BASE + w.index);
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

        nm.notify(ID_BASE + w.index, build(w, s));
        alertOnTransition(w, w.prev, s);
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
        Notification n = new Notification.Builder(this, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_printer)
                .setContentTitle(w.client.label() + " — " + title)
                .setContentText(text)
                .setContentIntent(openMachine(w))
                .setAutoCancel(true)
                .build();
        nm.notify(ID_ALERT + w.index, n);
    }

    private PendingIntent openMachine(Watch w) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(w.client.clickUrl()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 10 + w.index, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification build(Watch w, Cosmos.Status s) {
        String label = w.client.label();
        Notification.Builder b = new Notification.Builder(this, CH_PROGRESS)
                .setSmallIcon(R.drawable.ic_printer)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setSortKey(String.format(Locale.ROOT, "%02d", w.index))
                .setContentIntent(openMachine(w));
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);

        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, PrintService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent app = PendingIntent.getActivity(this, 3,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        b.addAction(new Notification.Action.Builder(null, "Arrêter le suivi", stop).build());
        b.addAction(new Notification.Action.Builder(null, "Réglages", app).build());

        String chip = null;
        if (s == null) {
            b.setContentTitle(label).setContentText("Connexion…").setProgress(0, 0, true);
        } else {
            String file = Cosmos.name(s.file);
            int pct = (int) Math.floor(s.progress * 100);
            String temps = temps(s);
            String ident = ident(s);
            switch (s.state) {
                case PRINTING:
                case PAUSED: {
                    boolean paused = s.state == Cosmos.State.PAUSED;
                    boolean heating = s.heating();
                    StringBuilder line = new StringBuilder();
                    if (paused) line.append("En pause · ");
                    if (heating) {
                        line.append("Chauffe · buse ").append(Math.round(s.ext)).append("/").append(Math.round(s.extT)).append(" °C");
                    } else {
                        line.append(pct).append(" %");
                        if (s.totalLayers > 0) line.append(" · couche ").append(s.layer).append("/").append(s.totalLayers);
                        if (s.remainingS >= 0) line.append(" · reste ").append(Cosmos.dur(s.remainingS));
                    }
                    b.setContentTitle(label + (file.isEmpty() ? "" : " — " + file))
                            .setContentText(line)
                            .setSubText(!heating && s.remainingS >= 0 ? "fin " + endTime(s.remainingS) : null);
                    if (heating) b.setProgress(0, 0, true);
                    else b.setProgress(1000, (int) (s.progress * 1000), false);
                    StringBuilder big = new StringBuilder(line);
                    if (!file.isEmpty()) big.append("\nFichier : ").append(file);
                    if (s.elapsedS >= 0) big.append("\nÉcoulé ").append(Cosmos.dur(s.elapsedS));
                    if (!temps.isEmpty()) big.append("\n").append(temps);
                    if (!ident.isEmpty()) big.append("\n").append(ident);
                    b.setStyle(new Notification.BigTextStyle().bigText(big));
                    chip = heating ? "Chauffe" : (paused ? "⏸ " + pct + " %" : pct + " %");
                    break;
                }
                case COMPLETE:
                    b.setContentTitle(label + " — Terminé ✓")
                            .setContentText(file + (s.elapsedS > 0 ? " · " + Cosmos.dur(s.elapsedS) : ""))
                            .setProgress(1000, 1000, false);
                    break;
                case CANCELLED:
                    b.setContentTitle(label + " — Annulé").setContentText(file);
                    break;
                case ERROR:
                    b.setContentTitle(label + " — Erreur").setContentText(s.message);
                    break;
                case OFFLINE:
                    b.setContentTitle(label + " — Injoignable").setContentText(s.message);
                    break;
                default:
                    b.setContentTitle(label + " — Prête")
                            .setContentText(temps.isEmpty() ? w.client.host() : temps);
                    if (!ident.isEmpty()) b.setStyle(new Notification.BigTextStyle()
                            .bigText((temps.isEmpty() ? "" : temps + "\n") + ident));
                    break;
            }
        }
        promote(b, chip);
        return b.build();
    }

    /** Android 16 : demande une notification "Live Update" avec pastille dans la barre d'état. */
    private static void promote(Notification.Builder b, String chip) {
        if (Build.VERSION.SDK_INT < 36) return;
        Bundle ex = new Bundle();
        ex.putBoolean("android.requestPromotedOngoing", chip != null);
        b.addExtras(ex);
        if (chip == null) return;
        try {
            Method m = Notification.Builder.class.getMethod("setShortCriticalText", String.class);
            m.invoke(b, chip);
        } catch (Exception ignored) { }
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
