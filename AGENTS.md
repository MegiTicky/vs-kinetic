# VS: Kinetic Agent Instructions

## Project Overview

- This is a Minecraft 1.20.1 Valkyrien Skies 2 addon.
- The primary supported loader is Forge.
- The project uses Kotlin, Architectury Loom, and Gradle 8.3.
- Do not commit generated Gradle caches or module build directories.

## Build

Run commands from `C:\Modding\VS_Kinetic`:

```powershell
.\gradlew.bat :forge:build
```

The deployable Forge artifact is:

```text
forge\build\libs\vs-kinetic-0.1.0.jar
```

Use the remapped release jar without a classifier. Do not deploy `-dev.jar`, `-dev-shadow.jar`, or source jars.

## Local Deployment

After a successful Forge build, deploy the release jar to this CurseForge instance:

```text
C:\Users\lauya\curseforge\minecraft\Instances\VS2.4Temp4\mods
```

PowerShell deployment command:

```powershell
Copy-Item -Force `
  ".\forge\build\libs\vs-kinetic-0.1.0.jar" `
  "C:\Users\lauya\curseforge\minecraft\Instances\VS2.4Temp4\mods\vs-kinetic-0.1.0.jar"
```

If the target directory does not exist, stop and report the missing path rather than creating a different instance directory. Do not delete or overwrite unrelated mods.

## Verification

- Run `.\gradlew.bat :forge:build` before deployment.
- Confirm the target jar exists after copying.
- If practical, run the target Forge instance and verify `/vskinetic status` in-game.
- Report build failures, missing dependencies, or runtime failures instead of claiming deployment succeeded.

## Git

- Remote: `https://github.com/MegiTicky/vs-kinetic.git`
- Default branch: `main`
- Do not commit generated files under `.gradle/`, `.kotlin/`, or `**/build/`.
- Do not commit or push unless explicitly requested.
