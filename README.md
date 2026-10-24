# Rotatieknop (rotation-fixer)

Brengt de "draai scherm"-knop terug die Android toont als automatisch draaien uit staat.

Staat automatisch draaien **uit** en houd je je telefoon fysiek in een andere stand dan het
scherm (bijv. liggend terwijl het scherm staand is), dan verschijnt er een rond draai-icoontje in
een hoek van het scherm. Tik erop en het scherm draait naar de stand waarin je de telefoon
vasthoudt. Automatisch draaien blijft uit.

## Installeren

1. Download [`apk/rotation-fixer.apk`](apk/rotation-fixer.apk) op je telefoon en installeer hem
   (sta "installeren uit onbekende bronnen" toe als daarom gevraagd wordt).
2. Open **Rotatieknop** en geef de twee toestemmingen:
   - **Weergeven over andere apps** – om het icoontje boven andere apps te tonen.
   - **Systeeminstellingen wijzigen** – om de schermstand te kunnen aanpassen.
   - Meldingen zijn optioneel (Android toont een stille melding zolang de service draait).
3. Laat **Draai-knop inschakelen** aan staan. De service start ook automatisch na een herstart.

Opties in de app:

- Ook ondersteboven (180°) voorstellen.
- Knop na 10 seconden automatisch verbergen.
- Hoek waarin de knop verschijnt. Die hoek geldt vanuit hoe je de telefoon vasthoudt: met
  "rechtsonder" staat de knop rechtsonder voor jou, ook als het scherm nog in de oude stand staat.

> Tip: werkt de knop niet op de achtergrond? Zet batterij-optimalisatie voor Rotatieknop uit
> (Instellingen › Apps › Rotatieknop › Batterij › Onbeperkt).

## Hoe het werkt

- `RotationService` is een foreground service die alleen de bewegingssensor gebruikt als het
  scherm aan staat én automatisch draaien uit staat.
- Is de fysieke stand ~0,4 s stabiel en anders dan de huidige schermstand, dan wordt een
  overlay-knop getoond.
- Een tik schrijft de gewenste stand naar `Settings.System.USER_ROTATION`, dezelfde instelling die
  de ingebouwde Android-rotatiesuggestie gebruikt.
- Weigert de app op de voorgrond die stand (bijv. een app die alleen staand werkt), dan wordt de
  knop niet opnieuw getoond tot je de telefoon anders draait.

## Zelf bouwen

```sh
sudo apt install aapt dalvik-exchange zipalign apksigner openjdk-21-jdk-headless
./build.sh            # resultaat: build/rotation-fixer.apk
```

Het script heeft geen Android SDK of Gradle nodig: het Android-framework wordt uit Robolectric's
`android-all` jar op Maven Central gehaald. De APK wordt gesigneerd met
`keystore/rotation-fixer.jks` (wachtwoord `rotationfixer`) zodat nieuwe versies over de oude heen
geïnstalleerd kunnen worden. Dit is een sleutel voor persoonlijk gebruik, niet geheim.
