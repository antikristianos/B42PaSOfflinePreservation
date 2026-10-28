# B42PaSOfflinePreservation

Maintained by Antikristianos. Developed with ChatGPT / Codex assistance.

Source for package `B42PaSOfflinePreservation`, game slice `42.20`, mod version `1.5`. Java 17 bytecode; use JDK 25 to read the game's dependencies.

This repository contains the Java files used by this artifact, its embedded data, and the associated runtime scripts. It excludes internal planning, reports, credentials, compiled mods and third-party dependencies.

## Build and verify

Supply your legally obtained game and ZombieBuddy files. Run:

```powershell
.\build.ps1 -GameRoot 'PATH_TO_GAME' -ZombieBuddyJar 'PATH_TO_ZombieBuddy.jar' -JdkRoot 'PATH_TO_JDK'
```

The script checks dependency hashes and every rebuilt class/resource against the published artifact. It writes only under `build`; it does not install, sign, or run the mod. Archive bytes may differ from the signed Workshop JAR.

`source-publication.json` identifies the Steam upload, branch, tag and original JAR SHA-256. Hashes and matching bytecode establish correspondence; they do not constitute a security audit.

## Distribution

Official Workshop links are recorded with each publication. Preserve any included third-party notices. No new blanket copyright claim or license is inferred from AI generation.
