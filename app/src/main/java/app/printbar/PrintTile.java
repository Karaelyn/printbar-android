package app.printbar;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Tuile des Réglages rapides : active / coupe le suivi. */
public class PrintTile extends TileService {

    @Override
    public void onStartListening() { update(); }

    @Override
    public void onClick() {
        if (PrintService.running) {
            PrintService.stop(this);
        } else {
            try {
                PrintService.start(this);
            } catch (Exception e) {
                // Démarrage en arrière-plan refusé : on passe par l'activité
                Intent i = new Intent(this, MainActivity.class)
                        .putExtra(MainActivity.EXTRA_START, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE));
                } else {
                    startActivityAndCollapse(i);
                }
            }
        }
        getQsTile().setState(PrintService.running ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        getQsTile().updateTile();
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(PrintService.running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            String sub = null;
            if (PrintService.running) {
                // Machines qui impriment : « 42 % · 7 % » ; sinon un résumé
                StringBuilder act = new StringBuilder();
                int ready = 0, off = 0, done = 0;
                for (PrintService.Watch w : PrintService.watches) {
                    Cosmos.Status s = w.last;
                    if (s == null) continue;
                    switch (s.state) {
                        case PRINTING: case PAUSED:
                            if (act.length() > 0) act.append(" · ");
                            act.append(s.heating() ? "chauffe" : (int) (s.progress * 100) + " %");
                            break;
                        case COMPLETE: done++; break;
                        case OFFLINE: off++; break;
                        default: ready++; break;
                    }
                }
                if (act.length() > 0) sub = act.toString();
                else if (done > 0) sub = "Terminé";
                else if (ready > 0) sub = ready > 1 ? ready + " prêtes" : "Prête";
                else if (off > 0) sub = "Injoignable";
            }
            t.setSubtitle(sub);
        }
        t.updateTile();
    }
}
