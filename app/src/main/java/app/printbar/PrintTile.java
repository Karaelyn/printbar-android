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
        Cosmos.Status s = PrintService.last;
        if (Build.VERSION.SDK_INT >= 29) {
            String sub = null;
            if (PrintService.running && s != null) {
                switch (s.state) {
                    case PRINTING: sub = (int) (s.progress * 100) + " %"; break;
                    case PAUSED: sub = "En pause"; break;
                    case COMPLETE: sub = "Terminé"; break;
                    case OFFLINE: sub = "Injoignable"; break;
                    default: sub = "Au repos"; break;
                }
            }
            t.setSubtitle(sub);
        }
        t.updateTile();
    }
}
