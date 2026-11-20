# Canon G3000 (G3501) – application Android (tuiles)

Application Kotlin / Jetpack Compose pour piloter une imprimante multifonction Canon PIXMA série G3000 (à réservoirs d'encre) en Wi-Fi.

## Ouvrir et lancer
1. Android Studio (Koala ou plus récent) → *Open* → dossier `CanonHub`.
2. Laissez Gradle synchroniser, puis lancez sur un téléphone Android 10+ (minSdk 29) connecté au même Wi-Fi que l'imprimante.
3. Dans **Réglages**, saisissez l'adresse IP de l'imprimante et appuyez sur **Tester**.

## Tuiles
| Tuile | Fonctionnement |
|---|---|
| Imprimer | PDF ou image ; fenêtre Android ou impression directe IPP (copies, couleur) ; recto-verso manuel ; partage depuis d'autres applis |
| Numériser | eSCL : A4, A5, 10×15, carte ; 150/300/600 dpi ; multipage ; recadrage auto, luminosité, contraste ; PDF, JPEG, partage |
| Copier | scan puis impression ; copies ; carte d'identité recto/verso sur une feuille |
| Encre et état | IPP : niveaux, état, alertes |
| Entretien | test des buses, nettoyages, alignement, rouleaux, platine (BJL via BJNP / RAW / IPP) ; historique |
| File d'attente | tâches en cours ou terminées, annulation |
| Infos | firmware, numéro de série, compteurs, formats acceptés |
| Réglages | IP, recherche mDNS, diagnostic, plusieurs imprimantes, alertes encre et anti-bouchage |

Widget « Canon : encre » : niveaux, Scan, Test des buses, actualisation.

## Limites connues
- Code non compilé ni testé sur l'imprimante : prévoyez de petits ajustements au premier essai.
- Le scan suppose que l'imprimante expose eSCL en HTTP. Si elle n'accepte que HTTPS, il faudra adapter `Escl` dans `Printer.kt`.
- Outils d'entretien expérimentaux : commandes BJL non documentées par Canon. En cas d'échec, changer le mode d'envoi ou passer par le bouton Stop de l'imprimante.
- Scan depuis le chargeur automatique non géré (vitre uniquement).
- Impression directe IPP : dépend des formats acceptés (voir Infos) ; repli automatique sur la fenêtre Android.
