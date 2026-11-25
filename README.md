# PrintBar Android

Avancement des imprimantes 3D (API CosmOS) dans une notification Android unique, calquée sur PrintBar PC : un segment par machine (nom, %, barre, temps restant + heure de fin), et une carte de détail par machine quand on déplie.

- Plusieurs machines (réglages : une adresse par ligne, ou « Nom = adresse »).
- Sans nom, une machine s'affiche « IP · fin du SN » (SN lu via /api/machine/device, ou /api/machine/profile sur les anciens logiciels CosmOS).
- Notification : fichier, %, couche, temps restant, heure de fin, températures buse/plateau/caisson, état « Chauffe », modèle, SN, version logicielle.
- Appui sur une notification : ouvre http://<ip>/mission-control-viewer/ de la machine.
- Android 16 : demande de « Live Update » (pastille « 42 % » dans la barre d'état).
- Alerte à la fin, en pause, en erreur ou si l'imprimante devient injoignable pendant une impression.
- Tuile Réglages rapides pour activer/couper le suivi.

## Installer
Chaque push sur `main` construit l'APK (GitHub Actions) et le publie dans **Releases**.
Sur le téléphone : Releases › dernière version › `PrintBar-vX.apk` › installer (autoriser l'installation depuis le navigateur).

Le téléphone doit être sur le même réseau que l'imprimante (ou via VPN).
