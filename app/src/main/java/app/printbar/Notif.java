package app.printbar;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.View;
import android.widget.RemoteViews;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Contenu de l'unique notification, calqué sur PrintBar PC :
 * vue réduite = un segment par machine côte à côte (nom, % ou état, barre, temps restant + heure de fin) ;
 * vue dépliée = une carte de détail par machine (comme la carte de survol).
 */
final class Notif {

    static final int MAX_SEGMENTS = 3; // au-delà, la vue réduite n'a plus la place ; tout reste dans la vue dépliée

    private Notif() { }

    // ---------- couleurs (mêmes teintes que la version PC, claires/sombres) ----------

    static boolean dark(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    static int accent(Cosmos.Status s, boolean dark) {
        if (s == null) return dark ? 0x80FFFFFF : 0x80000000;
        switch (s.state) {
            case PRINTING: return s.heating() ? (dark ? 0xFFFF9F43 : 0xFFC45C00) : (dark ? 0xFF4CC2FF : 0xFF0067C0);
            case PAUSED: return dark ? 0xFFFFB900 : 0xFF9D5D00;
            case COMPLETE: return dark ? 0xFF6CCB5F : 0xFF0F7B0F;
            case ERROR: case CANCELLED: return dark ? 0xFFFF99A4 : 0xFFC42B1C;
            default: return dark ? 0x80FFFFFF : 0x80000000;
        }
    }

    static int track(boolean dark) { return dark ? 0x33FFFFFF : 0x26000000; }
    static int fg2(boolean dark) { return dark ? 0xC5FFFFFF : 0x9E000000; }

    // ---------- textes (mêmes formulations que la version PC) ----------

    static String stateText(Cosmos.Status s) {
        if (s == null) return "Connexion…";
        switch (s.state) {
            case PRINTING: return s.heating() ? "Chauffe" : "Impression";
            case PAUSED: return "En pause";
            case COMPLETE: return "Terminé";
            case CANCELLED: return "Annulé";
            case ERROR: return "Erreur";
            case IDLE: return "Prête";
            default: return "Hors ligne";
        }
    }

    /** Ligne 1, à droite : « 42 % », « ⏸ 55 % », « 180° » en chauffe, ✓ ✕ ! */
    static String right(Cosmos.Status s) {
        if (s == null) return "";
        int pct = (int) Math.floor(s.progress * 100);
        switch (s.state) {
            case PRINTING: return s.heating() ? Math.round(s.ext) + "°" : pct + " %";
            case PAUSED: return "⏸ " + pct + " %";
            case COMPLETE: return "✓";
            case CANCELLED: return "✕";
            case ERROR: return "!";
            default: return "";
        }
    }

    /** Ligne 2 : « reste 43 min · fin 16:20 », « Terminé · 3 h 05 », « Prête »… */
    static String line2(Cosmos.Status s) {
        if (s == null) return "Connexion…";
        switch (s.state) {
            case PRINTING:
                if (s.heating()) return "Chauffe";
                return s.remainingS >= 0 ? "reste " + Cosmos.dur(s.remainingS) + " · fin " + end(s.remainingS) : Cosmos.name(s.file);
            case PAUSED:
                return "En pause" + (s.remainingS >= 0 ? " · reste " + Cosmos.dur(s.remainingS) : "");
            case COMPLETE:
                return "Terminé" + (s.elapsedS > 0 ? " · " + Cosmos.dur(s.elapsedS) : "");
            default:
                return stateText(s);
        }
    }

    static String end(long remainingS) {
        Calendar c = Calendar.getInstance();
        int today = c.get(Calendar.DAY_OF_YEAR);
        c.add(Calendar.SECOND, (int) remainingS);
        String hm = String.format(Locale.FRANCE, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        return c.get(Calendar.DAY_OF_YEAR) == today ? hm : "dem. " + hm;
    }

    /** Sous-titre de l'en-tête : « 2 en cours · prochaine fin 16:20 », « 2 prêtes »… */
    static String summary(List<PrintService.Watch> ws) {
        int active = 0, done = 0, ready = 0, off = 0;
        long next = -1;
        for (PrintService.Watch w : ws) {
            Cosmos.Status s = w.last;
            if (s == null) continue;
            if (s.active()) {
                active++;
                if (s.state == Cosmos.State.PRINTING && !s.heating() && s.remainingS >= 0 && (next < 0 || s.remainingS < next)) next = s.remainingS;
            } else if (s.state == Cosmos.State.COMPLETE) done++;
            else if (s.state == Cosmos.State.OFFLINE) off++;
            else ready++;
        }
        StringBuilder b = new StringBuilder();
        if (active > 0) {
            b.append(active).append(" en cours");
            if (next >= 0) b.append(" · ").append(active > 1 ? "prochaine fin " : "fin ").append(end(next));
        } else if (done > 0) b.append(done > 1 ? done + " terminées" : "Terminé");
        else if (ready > 0) b.append(ready > 1 ? ready + " prêtes" : "Prête");
        else if (off > 0) b.append("Hors ligne");
        return b.toString();
    }

    // ---------- vues ----------

    static RemoteViews bar(Context c, List<PrintService.Watch> ws) {
        boolean dark = dark(c);
        RemoteViews root = new RemoteViews(c.getPackageName(), R.layout.notif_bar);
        root.removeAllViews(R.id.segments);
        int n = Math.min(ws.size(), MAX_SEGMENTS);
        for (int i = 0; i < n; i++) {
            PrintService.Watch w = ws.get(i);
            Cosmos.Status s = w.last;
            RemoteViews seg = new RemoteViews(c.getPackageName(), R.layout.segment);
            seg.setViewVisibility(R.id.seg_sep, i == 0 ? View.GONE : View.VISIBLE);
            boolean dim = s == null || s.state == Cosmos.State.IDLE || s.state == Cosmos.State.OFFLINE;
            int acc = accent(s, dark);

            seg.setTextViewText(R.id.seg_name, w.client.label());
            if (dim) seg.setTextColor(R.id.seg_name, fg2(dark));
            String r = right(s);
            seg.setTextViewText(R.id.seg_right, r);
            seg.setViewVisibility(R.id.seg_right, r.isEmpty() ? View.GONE : View.VISIBLE);
            if (s != null && !(s.state == Cosmos.State.PRINTING && !s.heating())) seg.setTextColor(R.id.seg_right, acc);

            bar(seg, R.id.seg_bar, s, acc, dark);

            seg.setTextViewText(R.id.seg_line2, line2(s));
            boolean colored = s != null && (s.state == Cosmos.State.COMPLETE || s.state == Cosmos.State.ERROR
                    || s.state == Cosmos.State.PAUSED || s.state == Cosmos.State.CANCELLED || s.heating());
            seg.setTextColor(R.id.seg_line2, colored ? acc : fg2(dark));

            seg.setOnClickPendingIntent(R.id.seg_root, open(c, w));
            root.addView(R.id.segments, seg);
        }
        return root;
    }

    static RemoteViews cards(Context c, List<PrintService.Watch> ws) {
        boolean dark = dark(c);
        boolean compact = ws.size() >= 3; // la vue dépliée est limitée en hauteur : moins de lignes par carte
        RemoteViews root = new RemoteViews(c.getPackageName(), R.layout.notif_cards);
        root.removeAllViews(R.id.cards);
        for (int i = 0; i < ws.size(); i++) {
            PrintService.Watch w = ws.get(i);
            Cosmos.Status s = w.last;
            RemoteViews card = new RemoteViews(c.getPackageName(), R.layout.card);
            card.setViewVisibility(R.id.card_sep, i == 0 ? View.GONE : View.VISIBLE);
            int acc = accent(s, dark);
            card.setTextViewText(R.id.card_name, w.client.label());
            String st = stateText(s);
            if (s != null && s.active() && !s.heating()) st += " · " + (int) Math.floor(s.progress * 100) + " %";
            card.setTextViewText(R.id.card_state, st);
            card.setTextColor(R.id.card_state, acc);
            bar(card, R.id.card_bar, s, acc, dark);
            card.setTextViewText(R.id.card_rows, rows(w, s, compact));
            card.setTextColor(R.id.card_rows, fg2(dark));
            card.setOnClickPendingIntent(R.id.card_root, open(c, w));
            root.addView(R.id.cards, card);
        }
        return root;
    }

    private static void bar(RemoteViews v, int id, Cosmos.Status s, int acc, boolean dark) {
        boolean heating = s != null && s.heating();
        double p = s == null ? 0 : (s.state == Cosmos.State.COMPLETE ? 1 : s.progress);
        v.setProgressBar(id, 1000, (int) Math.round(p * 1000), heating || s == null);
        if (Build.VERSION.SDK_INT >= 31) {
            v.setColorStateList(id, "setProgressTintList", ColorStateList.valueOf(acc));
            v.setColorStateList(id, "setIndeterminateTintList", ColorStateList.valueOf(acc));
            v.setColorStateList(id, "setProgressBackgroundTintList", ColorStateList.valueOf(track(dark)));
        }
    }

    /** Lignes de la carte : libellé en gras, valeur à la suite (comme la carte de survol PC). */
    private static CharSequence rows(PrintService.Watch w, Cosmos.Status s, boolean compact) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        if (s == null) { row(b, "Adresse", w.client.host()); return b; }
        String file = Cosmos.name(s.file);
        if (s.active()) {
            StringBuilder p = new StringBuilder();
            if (s.totalLayers > 0) p.append("couche ").append(s.layer).append("/").append(s.totalLayers);
            if (s.elapsedS >= 0) p.append(p.length() > 0 ? " · " : "").append("écoulé ").append(Cosmos.dur(s.elapsedS));
            if (s.remainingS >= 0) p.append(" · reste ").append(Cosmos.dur(s.remainingS)).append(" · fin ").append(end(s.remainingS));
            if (p.length() > 0) row(b, "Temps", p.toString());
            if (!file.isEmpty()) row(b, "Fichier", file);
        } else if (s.state == Cosmos.State.COMPLETE || s.state == Cosmos.State.CANCELLED) {
            if (!file.isEmpty()) row(b, "Fichier", file + (s.elapsedS > 0 ? " · " + Cosmos.dur(s.elapsedS) : ""));
        } else if (!s.message.isEmpty() && s.state != Cosmos.State.IDLE) {
            row(b, "Détail", s.message);
        }
        if (s.hasTemps && s.state != Cosmos.State.OFFLINE) {
            StringBuilder t = new StringBuilder("buse ").append(Math.round(s.ext)).append("°");
            if (s.extT > 0) t.append("/").append(Math.round(s.extT)).append("°");
            t.append(" · plateau ").append(Math.round(s.bed)).append("°");
            if (s.bedT > 0) t.append("/").append(Math.round(s.bedT)).append("°");
            if (s.chamber != null) t.append(" · caisson ").append(Math.round(s.chamber)).append("°");
            row(b, "Temp.", t.toString());
        }
        if (!compact) {
            StringBuilder m = new StringBuilder();
            if (!s.model.isEmpty()) m.append(s.model);
            if (!s.serial.isEmpty()) m.append(m.length() > 0 ? " · " : "").append("SN ").append(s.serial);
            if (!s.firmware.isEmpty()) m.append(m.length() > 0 ? " · " : "").append("logiciel ").append(s.firmware);
            if (!w.client.name.isEmpty()) m.append(m.length() > 0 ? " · " : "").append(w.client.host());
            if (m.length() > 0) row(b, "Machine", m.toString());
        }
        if (b.length() == 0) row(b, "Adresse", w.client.host());
        return b;
    }

    private static void row(SpannableStringBuilder b, String label, String value) {
        if (b.length() > 0) b.append("\n");
        int st = b.length();
        b.append(label).append("  ");
        b.setSpan(new StyleSpan(Typeface.BOLD), st, st + label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.append(value);
    }

    static PendingIntent open(Context c, PrintService.Watch w) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(w.client.clickUrl()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 10 + w.index, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
