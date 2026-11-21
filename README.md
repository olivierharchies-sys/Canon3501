# Canon G3000 (G3501) – application Android (tuiles)

Application Kotlin / Jetpack Compose pour piloter une imprimante multifonction Canon PIXMA série G3000 (à réservoirs d'encre) en Wi-Fi.

## Ouvrir et lancer
1. Android Studio (Koala ou plus récent) → *Open* → dossier `CanonHub`.
2. Laissez Gradle synchroniser, puis lancez sur un téléphone Android 10+ (minSdk 29) connecté au même Wi-Fi que l'imprimante.
3. Dans **Réglages**, saisissez l'adresse IP de l'imprimante et appuyez sur **Tester**.

## Organisation (v2.0)
Navigation basse : **Accueil · Imprimer · Copier · Numériser · Documents**.

| Écran | Contenu |
|---|---|
| Accueil | État de l'imprimante, actions rapides (Imprimer, Copier, Numériser), outils (Entretien, Encre, File, Documents, Infos, Réglages), activité |
| Imprimer | Document avec aperçu → voie d'envoi (Pilote intégré, Service Android, Canon PRINT, IPP) → papier, mise en page, qualité/couleur, copies et pages, recto-verso manuel, planche photos |
| Copier | Type d'original (A4, A5, photo, carte ID R/V), exemplaires, couleur, qualité, papier ; immédiate ou par lot ; imprimée par le pilote intégré |
| Numériser | Multipage, retouche, OCR, PDF/JPEG, classement |

### Pilote intégré (Gutenprint) – équivalences avec le service Canon
Format, type de papier, alimentation, résolution, qualité (brouillon/standard/élevée), couleur ou N&B,
orientation (auto/portrait/paysage), pages par feuille (1/2/4), sans bordure, remplissage,
exemplaires (1 à 99), assemblage, plage de pages (« 1-3, 5 »), ordre inverse, recto-verso manuel.

Widget « Canon : encre » : niveaux, Scan, Test des buses, actualisation.

## Constat sur la G3501 (diagnostic)
- Ouverts : 80, 443, LPD 515 (file « auto »), BJNP UDP 8611/8612.
- Absents : IPP (631), eSCL, RAW 9100.
- Conséquences : impression via le service Canon d'Android ; outils d'entretien via LPD ; pas de niveaux d'encre réseau ; scan non disponible sans protocole BJNP scanner.

## Limites connues
- Code non compilé ni testé sur l'imprimante : prévoyez de petits ajustements au premier essai.
- Le scan suppose que l'imprimante expose eSCL en HTTP. Si elle n'accepte que HTTPS, il faudra adapter `Escl` dans `Printer.kt`.
- Outils d'entretien expérimentaux : commandes BJL non documentées par Canon. En cas d'échec, changer le mode d'envoi ou passer par le bouton Stop de l'imprimante.
- Scan depuis le chargeur automatique non géré (vitre uniquement).
- Impression directe IPP : dépend des formats acceptés (voir Infos) ; repli automatique sur la fenêtre Android.
