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

    private EditText url, poll;
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
        sub.setText("Avancement de l'imprimante 3D dans les notifications. Le téléphone doit être sur le même réseau que l'imprimante.");
        sub.setPadding(0, dp(4), 0, dp(20));
        root.addView(sub);

        root.addView(label("Adresse de l'imprimante"));
        url = new EditText(this);
        url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(sp.getString(Prefs.URL, Prefs.DEFAULT_URL));
        root.addView(url);

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

        Button web = new Button(this);
        web.setText("Ouvrir l'interface de l'imprimante");
        web.setOnClickListener(v -> {
            save();
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(new Cosmos(url.getText().toString()).baseUrl())));
        });
        root.addView(web, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setPadding(0, dp(20), 0, 0);
        status.setTextIsSelectable(true);
        root.addView(status);

        TextView tip = new TextView(this);
        tip.setText("Astuce : ajoute la tuile « PrintBar » dans les Réglages rapides pour activer/couper le suivi sans ouvrir l'appli.");
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
        Prefs.get(this).edit()
                .putString(Prefs.URL, url.getText().toString().trim())
                .putInt(Prefs.POLL, p)
                .apply();
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
        Cosmos.Status s = PrintService.last;
        if (!PrintService.running) {
            status.setText("Suivi arrêté.");
            return;
        }
        if (s == null) {
            status.setText("Connexion…");
            return;
        }
        long ago = (System.currentTimeMillis() - PrintService.lastAt) / 1000;
        StringBuilder t = new StringBuilder();
        t.append("État : ").append(label(s.state));
        if (!s.file.isEmpty()) t.append("\nFichier : ").append(Cosmos.name(s.file));
        if (s.state == Cosmos.State.PRINTING || s.state == Cosmos.State.PAUSED) {
            t.append("\nProgression : ").append((int) (s.progress * 100)).append(" %");
            if (s.totalLayers > 0) t.append(" · couche ").append(s.layer).append("/").append(s.totalLayers);
            if (s.elapsedS >= 0) t.append("\nÉcoulé : ").append(Cosmos.dur(s.elapsedS));
            if (s.remainingS >= 0) t.append("\nRestant : ").append(Cosmos.dur(s.remainingS));
        }
        if (s.hasTemps) t.append("\nBuse ").append(Math.round(s.ext)).append(" °C · Plateau ").append(Math.round(s.bed)).append(" °C");
        if (!s.message.isEmpty()) t.append("\nDétail : ").append(s.message);
        t.append("\n\nDernière mise à jour il y a ").append(ago).append(" s");
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
