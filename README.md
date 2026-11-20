# Canon G3000 (G3501) – application Android (tuiles)

Application Kotlin / Jetpack Compose pour piloter une imprimante multifonction Canon PIXMA série G3000 (à réservoirs d'encre) en Wi-Fi.

## Ouvrir et lancer
1. Android Studio (Koala ou plus récent) → *Open* → dossier `CanonHub`.
2. Laissez Gradle synchroniser, puis lancez sur un téléphone Android 10+ (minSdk 29) connecté au même Wi-Fi que l'imprimante.
3. Dans **Réglages**, saisissez l'adresse IP de l'imprimante et appuyez sur **Tester**.

## Tuiles
| Tuile | Fonctionnement |
|---|---|
| Imprimer | PDF ou image, via la fenêtre d'impression Android (service Canon ou Mopria) |
| Numériser | eSCL / AirScan (HTTP, port 80) depuis la vitre, A4, 150/300/600 dpi, couleur ou N&B ; JPEG, PDF, partage |
| Copier | numérisation puis impression |
| Encre et état | IPP (port 631) : niveaux par cartouche, état, alertes |
| Entretien | test des buses, nettoyage (tout/noir/couleur), nettoyage en profondeur, alignement, rouleaux, platine — commandes Canon BJL via BJNP (8611), RAW (9100) ou IPP ; historique des lancements |
| Réglages | adresse IP, recherche automatique (mDNS), diagnostic des ports |

## Limites connues
- Code non compilé ni testé sur l'imprimante : prévoyez de petits ajustements au premier essai.
- Le scan suppose que l'imprimante expose eSCL en HTTP. Si elle n'accepte que HTTPS, il faudra adapter `Escl` dans `Printer.kt`.
- Outils d'entretien expérimentaux : commandes BJL non documentées par Canon. En cas d'échec, changer le mode d'envoi ou passer par le bouton Stop de l'imprimante.
- Scan depuis le chargeur automatique non géré (vitre uniquement).
