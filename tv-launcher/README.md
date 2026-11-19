# My Home – a custom home screen for Sony (Google TV / Android TV)

Your own TV home screen: a big clock, a row of **favourites** you choose and arrange, every app in a
grid, seven colour themes, and no ads or recommendations.

## My apps

By default the home screen shows just **Continue watching** (Jellyfin) and **My apps**:
Jellyfin, MLB, NBA, ThreeNow, TVNZ+ and YouTube. They're found by name the first time My Home
opens; any that aren't installed are listed on screen.

- Change the list: **Options → Choose My apps…**
- Reorder or remove one: press and hold OK on it
- Get the full Favourites + All apps screen back: **Options → Show Favourites and All apps again**

## Home Assistant

The header shows **outside temperature, solar production and battery %**, and a **Home** button
opens the Home Assistant **TV** dashboard full screen (arrows move, OK presses, Back closes pop-ups
and returns to My Home).

Setup, once:
1. In Home Assistant: your profile → **Security** → **Long-lived access tokens** → **Create token**
   (name it "TV"). Copy it.
2. On your computer: `bash tv.sh ha-setup 192.168.1.5:8123 <paste token>` (use your own address).

Change or disconnect later in **Options → Home Assistant**.

## Using it (with the TV remote)

| What | How |
| --- | --- |
| Open an app | Select it and press **OK** |
| Add / remove a favourite, move it left or right, hide, uninstall | **Press and hold OK** on the app (or press the remote's menu button) |
| Change the background | **Theme** button at the top |
| Apps per row, hidden apps, Home button on/off, go back to Google's home | **Options** button at the top |
| Back to the top | **Back** or **Home** |

## Continue watching from Jellyfin

**Options → Jellyfin: sign in for Continue watching**, then enter your server (e.g. `192.168.1.10:8096`),
username and password. A **Continue watching** row appears at the top with everything you're part-way
through (with a progress bar) plus the next episode of shows you're following. Select one and the
Jellyfin app opens on that film or episode – press **Resume**. The row refreshes every time you come
back to the home screen. Only a sign-in token is saved, never your password; sign out from Options.
Needs the Jellyfin app from the Play Store.

## Installing it

The newest build is always here (GitHub builds it automatically):
**https://github.com/rmwatson/Tutorial/releases/download/my-home-latest/MyHome.apk**

### Easiest: from your computer, with a backup first

1. On the TV: **Settings → System → About**, press OK on **Android TV OS build** 7 times.
   Then **Settings → System → Developer options → USB debugging: ON**.
2. Download this folder (or the two script files) to your computer, then run:
   - **Windows** (PowerShell): `powershell -ExecutionPolicy Bypass -File .\tv.ps1 install`
   - **Mac / Linux** (Terminal): `./tv.sh install`
3. When the TV asks to allow the connection, choose **Always allow** and press OK.

The script uses the TV at `192.168.1.42` (change it with `-TV x.x.x.x` / `TV=x.x.x.x ./tv.sh …`). It:

1. **Backs up first** – saves the TV's app list and all its settings into `tv-backup/`
   (the first one is kept as `tv-backup/original`). Nothing on the TV is removed or disabled.
2. Installs My Home.
3. Switches on the **My Home – Home button** accessibility service, so the remote's Home button
   brings you to My Home. (Google TV doesn't let you pick another home app any other way.)

### Stop Google's home screen flashing

`bash tv.sh make-default` makes My Home the TV's real home app, so leaving an app goes straight
back to it. If the TV won't accept that (or forgets after a restart),
`bash tv.sh make-default --disable-google-home` switches Google's home screen off – it stays
installed and `bash tv.sh restore-home` switches it back on.

### Undo everything

`.\tv.ps1 revert` (Windows) or `./tv.sh revert` (Mac/Linux) switches the service off, uninstalls
My Home, and checks the TV against the original backup.

Without a computer: **Settings → System → Accessibility → My Home – Home button → Off**, then
**Settings → Apps → My Home → Uninstall**.

### Without a computer

Install the free **Downloader** app on the TV, open the link above in it and install the APK
(allow Downloader to install unknown apps when asked). Then switch on
**Settings → System → Accessibility → My Home – Home button**.

## Building it yourself

`./gradlew assembleRelease` (needs the Android SDK). Code is in
`app/src/main/java/com/myhome/tvlauncher/`.
