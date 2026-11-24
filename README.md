# PrintBar Android

Avancement de l'imprimante 3D (API CosmOS) dans une notification Android avec barre de progression.

- Notification persistante : fichier, %, couche, temps restant, heure de fin, températures.
- Android 16 : demande de « Live Update » (pastille « 42 % » dans la barre d'état).
- Alerte à la fin, en pause, en erreur ou si l'imprimante devient injoignable pendant une impression.
- Tuile Réglages rapides pour activer/couper le suivi.

## Installer
Chaque push sur `main` construit l'APK (GitHub Actions) et le publie dans **Releases**.
Sur le téléphone : Releases › dernière version › `PrintBar-vX.apk` › installer (autoriser l'installation depuis le navigateur).

Le téléphone doit être sur le même réseau que l'imprimante (ou via VPN).
