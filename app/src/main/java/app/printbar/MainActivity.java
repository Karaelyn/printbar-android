package app.printbar;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Réglages : adresse de l'imprimante, intervalle, démarrage / arrêt du suivi. */
public class MainActivity extends Activity {

    static final String EXTRA_START = "start";

    private EditText printers, poll;
    private Button toggle;
    private TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean startPending;

    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); ui.postDelayed(this, 1000); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        PrintService.createChannels(this);
        SharedPreferences sp = Prefs.get(this);

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad + dp(24), pad, pad);

        TextView title = new TextView(this);
        title.setText("PrintBar");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Avancement des imprimantes 3D dans les notifications, une par machine. Le téléphone doit être sur le même réseau que les machines.");
        sub.setPadding(0, dp(4), 0, dp(20));
        root.addView(sub);

        root.addView(label("Machines : une adresse par ligne, ou « Nom = adresse ». Sans nom, la machine s'affiche « IP · fin du SN »."));
        printers = new EditText(this);
        printers.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_VARIATION_URI);
        printers.setMinLines(2);
        printers.setGravity(Gravity.TOP | Gravity.START);
        printers.setText(Prefs.printersText(this));
        root.addView(printers);

        root.addView(label("Intervalle d'interrogation (s), écran allumé"));
        poll = new EditText(this);
        poll.setSingleLine(true);
        poll.setInputType(InputType.TYPE_CLASS_NUMBER);
        poll.setText(String.valueOf(sp.getInt(Prefs.POLL, 5)));
        root.addView(poll);

        toggle = new Button(this);
        toggle.setOnClickListener(v -> onToggle());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(16);
        root.addView(toggle, lp);

        Button battery = new Button(this);
        battery.setText("Autoriser le suivi en arrière-plan (batterie)");
        battery.setOnClickListener(v -> askBattery());
        root.addView(battery, new LinearLayout.LayoutParams(-1, -2));


        status = new TextView(this);
        status.setPadding(0, dp(20), 0, 0);
        status.setTextIsSelectable(true);
        root.addView(status);

        TextView tip = new TextView(this);
        tip.setText("Appuyer sur la notification d'une machine ouvre sa page Mission Control.\nAstuce : ajoute la tuile « PrintBar » dans les Réglages rapides pour activer/couper le suivi sans ouvrir l'appli.");
        tip.setPadding(0, dp(20), 0, 0);
        tip.setAlpha(0.7f);
        root.addView(tip);

        ScrollView sv = new ScrollView(this);
        sv.setFitsSystemWindows(true);
        sv.addView(root);
        setContentView(sv);

        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_START, false)) startTracking();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i.getBooleanExtra(EXTRA_START, false)) startTracking();
    }

    @Override protected void onResume() { super.onResume(); ui.post(tick); }
    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(tick); }

    private void onToggle() {
        if (PrintService.running) {
            PrintService.stop(this);
            ui.postDelayed(this::refresh, 300);
        } else {
            startTracking();
        }
    }

    private void startTracking() {
        save();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            startPending = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            return;
        }
        PrintService.start(this);
        ui.postDelayed(this::refresh, 300);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 1 && startPending) {
            startPending = false;
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
                PrintService.start(this);
            } else {
                status.setText("Sans l'autorisation des notifications, la barre ne peut pas s'afficher.");
            }
        }
    }

    private void save() {
        int p;
        try { p = Integer.parseInt(poll.getText().toString().trim()); } catch (Exception e) { p = 5; }
        p = Math.max(2, Math.min(60, p));
        String list = printers.getText().toString().trim();
        if (Prefs.parse(list).isEmpty()) list = Prefs.DEFAULT_PRINTERS;
        Prefs.get(this).edit()
                .putString(Prefs.PRINTERS, list)
                .putInt(Prefs.POLL, p)
                .apply();
        // Suivi déjà lancé : on le relance avec la nouvelle liste
        if (PrintService.running) PrintService.start(this);
    }

    private void askBattery() {
        PowerManager pm = getSystemService(PowerManager.class);
        try {
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } else {
                status.setText("Déjà autorisé : le suivi n'est pas limité par l'économie de batterie.");
            }
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void refresh() {
        toggle.setText(PrintService.running ? "Arrêter le suivi" : "Démarrer le suivi");
        if (!PrintService.running) {
            status.setText("Suivi arrêté.");
            return;
        }
        StringBuilder t = new StringBuilder();
        for (PrintService.Watch w : PrintService.watches) {
            if (t.length() > 0) t.append("\n\n");
            t.append(w.client.label()).append("\n");
            Cosmos.Status s = w.last;
            if (s == null) { t.append("Connexion…"); continue; }
            t.append("État : ").append(s.heating() ? "chauffe" : label(s.state));
            if (!s.file.isEmpty()) t.append("\nFichier : ").append(Cosmos.name(s.file));
            if (s.active() && !s.heating()) {
                t.append("\nProgression : ").append((int) (s.progress * 100)).append(" %");
                if (s.totalLayers > 0) t.append(" · couche ").append(s.layer).append("/").append(s.totalLayers);
                if (s.elapsedS >= 0) t.append("\nÉcoulé : ").append(Cosmos.dur(s.elapsedS));
                if (s.remainingS >= 0) t.append("\nRestant : ").append(Cosmos.dur(s.remainingS));
            }
            String temps = PrintService.temps(s);
            if (!temps.isEmpty()) t.append("\n").append(temps);
            String id = PrintService.ident(s);
            if (!id.isEmpty()) t.append("\n").append(id);
            if (!s.message.isEmpty()) t.append("\nDétail : ").append(s.message);
            t.append("\nMis à jour il y a ").append((System.currentTimeMillis() - w.lastAt) / 1000).append(" s");
        }
        status.setText(t);
    }

    private static String label(Cosmos.State st) {
        switch (st) {
            case PRINTING: return "impression en cours";
            case PAUSED: return "en pause";
            case COMPLETE: return "terminée";
            case CANCELLED: return "annulée";
            case ERROR: return "erreur";
            case OFFLINE: return "injoignable";
            default: return "au repos";
        }
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setAlpha(0.7f);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
