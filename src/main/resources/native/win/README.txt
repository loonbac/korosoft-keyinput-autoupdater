Windows native update helper (korosoft-update-helper.exe).

This binary is produced by build.sh in src/main/native/update_helper/
(with a mingw-w64 toolchain: apt install g++-mingw-w64-x86-64) and copied
into this directory, which Gradle packages into the mod jar as a resource.

Contract (see ModUpdater.replaceJar / launchWindowsHelper):
  korosoft-update-helper.exe <parentPid> <installedJar> <newJar> <resultFile>
waits for the game JVM (parentPid) to exit, moves the installed jar aside to
.bak, moves the downloaded <newJar> (<jar>.update) into place, restores the
installed jar on failure, and writes "OK" / "ERR:<reason>" to <resultFile>,
which the next boot consumes (ModUpdater.consumeWindowsHelperResult).

IMPORTANT (fixed 2026-08-10): <installedJar> must be the jar that EXISTS (the
one Fabric loaded). Passing a never-created "<jar>.old" makes the helper take
its failure path and silently delete the downloaded jar.
