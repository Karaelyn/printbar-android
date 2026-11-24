package app.printbar;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import java.util.Locale;

/** Service de premier plan : interroge l'imprimante et met à jour la notification de progression. */
public class PrintService extends Service {

    public static final String ACTION_STOP = "app.printbar.STOP";
    static final String CH_PROGRESS = "progress";
    static final String CH_ALERTS = "alerts";
    static final int ID_PROGRESS = 1;
    static final int ID_ALERT = 2;

    /** Dernier état connu, lu par l'activité. */
    public static volatile Cosmos.Status last;
    public static volatile long lastAt;
    public static volatile boolean running;

    private HandlerThread thread;
    private Handler handler;
    private Cosmos client;
    private NotificationManager nm;
    private PowerManager pm;
    private PowerManager.WakeLock wake;
    private Cosmos.State prevState;
    private int pollSec;
    private int offlineCount;

    public static void start(Context c) {
        Intent i = new Intent(c, PrintService.class);
        c.startForegroundService(i);
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
        p.setDescription("Barre d'avancement de l'impression en cours");
        p.setShowBadge(false);
        nm.createNotificationChannel(p);
        NotificationChannel a = new NotificationChannel(CH_ALERTS, "Alertes", NotificationManager.IMPORTANCE_HIGH);
        a.setDescription("Fin d'impression, pause, erreur, imprimante injoignable");
        nm.createNotificationChannel(a);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }
        SharedPreferences sp = Prefs.get(this);
        client = new Cosmos(sp.getString(Prefs.URL, Prefs.DEFAULT_URL));
        pollSec = Math.max(2, sp.getInt(Prefs.POLL, 5));

        Notification n = build(null);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ID_PROGRESS, n);
        }
        running = true;
        Prefs.get(this).edit().putBoolean(Prefs.ENABLED, true).apply();
        refreshTile();

        if (thread == null) {
            thread = new HandlerThread("printbar-poll");
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        handler.removeCallbacksAndMessages(null);
        handler.post(this::poll);
        return START_STICKY;
    }

    private void shutdown() {
        running = false;
        Prefs.get(this).edit().putBoolean(Prefs.ENABLED, false).apply();
        if (handler != null) handler.removeCallbacksAndMessages(null);
        releaseWake();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        refreshTile();
    }

    private void refreshTile() {
        try { TileService.requestListeningState(this, new ComponentName(this, PrintTile.class)); } catch (Exception ignored) { }
    }

    private void poll() {
        Cosmos.Status s = client.fetch();
        last = s;
        lastAt = System.currentTimeMillis();

        // Une erreur réseau isolée ne fait pas basculer en "hors ligne" pendant une impression
        if (s.state == Cosmos.State.OFFLINE) offlineCount++; else offlineCount = 0;
        boolean wasActive = prevState == Cosmos.State.PRINTING || prevState == Cosmos.State.PAUSED;
        if (s.state == Cosmos.State.OFFLINE && wasActive && offlineCount < 3) {
            schedule(true);
            return;
        }

        nm.notify(ID_PROGRESS, build(s));
        alertOnTransition(prevState, s);
        prevState = s.state;

        boolean active = s.state == Cosmos.State.PRINTING || s.state == Cosmos.State.PAUSED;
        if (active) acquireWake(); else releaseWake();
        schedule(active);
    }

    private void schedule(boolean active) {
        // Écran éteint : on espace les requêtes (la notification n'est pas visible)
        int sec = pm.isInteractive() ? pollSec : (active ? 30 : 60);
        handler.postDelayed(this::poll, sec * 1000L);
    }

    private void acquireWake() {
        if (wake == null) {
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "printbar:poll");
            wake.setReferenceCounted(false);
        }
        if (!wake.isHeld()) wake.acquire(26 * 3600 * 1000L);
    }

    private void releaseWake() {
        if (wake != null && wake.isHeld()) wake.release();
    }

    private void alertOnTransition(Cosmos.State prev, Cosmos.Status s) {
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
            case ERROR: title = "Erreur imprimante"; text = s.message; break;
            case OFFLINE: if (wasActive) { title = "Imprimante injoignable"; text = s.message; } break;
            case IDLE: if (wasActive) title = "Impression arrêtée"; break;
            default: break;
        }
        if (title == null) return;
        Notification n = new Notification.Builder(this, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_printer)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPrinter())
                .setAutoCancel(true)
                .build();
        nm.notify(ID_ALERT, n);
    }

    private PendingIntent openPrinter() {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(client != null ? client.baseUrl() : Prefs.DEFAULT_URL));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification build(Cosmos.Status s) {
        Notification.Builder b = new Notification.Builder(this, CH_PROGRESS)
                .setSmallIcon(R.drawable.ic_printer)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(openPrinter());
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);

        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, PrintService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent app = PendingIntent.getActivity(this, 3,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        b.addAction(new Notification.Action.Builder(null, "Arrêter le suivi", stop).build());
        b.addAction(new Notification.Action.Builder(null, "Réglages", app).build());

        String chip = null;
        if (s == null) {
            b.setContentTitle("PrintBar").setContentText("Connexion à l'imprimante…").setProgress(0, 0, true);
        } else {
            String file = Cosmos.name(s.file);
            int pct = (int) Math.floor(s.progress * 100);
            String temps = temps(s);
            switch (s.state) {
                case PRINTING:
                case PAUSED: {
                    boolean paused = s.state == Cosmos.State.PAUSED;
                    StringBuilder line = new StringBuilder();
                    line.append(pct).append(" %");
                    if (s.totalLayers > 0) line.append(" · couche ").append(s.layer).append("/").append(s.totalLayers);
                    if (s.remainingS >= 0) line.append(" · reste ").append(Cosmos.dur(s.remainingS));
                    if (paused) line.insert(0, "En pause · ");
                    b.setContentTitle(file.isEmpty() ? "Impression en cours" : file)
                            .setContentText(line)
                            .setSubText(s.remainingS >= 0 ? "fin " + endTime(s.remainingS) : null)
                            .setProgress(1000, (int) (s.progress * 1000), false);
                    String big = line + (temps.isEmpty() ? "" : "\n" + temps)
                            + (s.elapsedS >= 0 ? "\nÉcoulé " + Cosmos.dur(s.elapsedS) : "");
                    b.setStyle(new Notification.BigTextStyle().bigText(big));
                    chip = paused ? "⏸ " + pct + " %" : pct + " %";
                    break;
                }
                case COMPLETE:
                    b.setContentTitle("Terminé ✓ " + file)
                            .setContentText(s.elapsedS > 0 ? "Durée " + Cosmos.dur(s.elapsedS) : "Impression terminée")
                            .setProgress(1000, 1000, false);
                    break;
                case CANCELLED:
                    b.setContentTitle("Annulé · " + file).setContentText(temps);
                    break;
                case ERROR:
                    b.setContentTitle("Erreur imprimante").setContentText(s.message);
                    break;
                case OFFLINE:
                    b.setContentTitle("Imprimante injoignable")
                            .setContentText(client.baseUrl() + " · " + s.message);
                    break;
                default:
                    b.setContentTitle("Imprimante au repos").setContentText(temps.isEmpty() ? client.baseUrl() : temps);
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
        try {
            Method m = Notification.Builder.class.getMethod("setShortCriticalText", String.class);
            m.invoke(b, chip);
        } catch (Exception ignored) { }
    }

    private static String temps(Cosmos.Status s) {
        if (!s.hasTemps) return "";
        StringBuilder t = new StringBuilder("Buse ").append(Math.round(s.ext));
        if (s.extT > 0) t.append("/").append(Math.round(s.extT));
        t.append(" °C · Plateau ").append(Math.round(s.bed));
        if (s.bedT > 0) t.append("/").append(Math.round(s.bedT));
        return t.append(" °C").toString();
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
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        releaseWake();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
