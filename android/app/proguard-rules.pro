# R8 keep rules for the release build.
#
# Read this before adding anything: most of the libraries here ship their own consumer rules,
# which are applied automatically and must NOT be duplicated. Moshi, Retrofit, Room, WorkManager
# and navigation-fragment all do. What follows is only what those rules provably do not cover.
#
# Nothing in the test suite exercises any of this. Unit tests run on the JVM and never invoke R8,
# so the only evidence these rules are right is the release APK running on a device. Treat a
# change here as needing that run, not a green `./gradlew build`.
#
# R8 runs in full mode: that is the AGP 8+ default and gradle.properties does not turn it off.
# Full mode is more aggressive about what it will strip and inline, which is worth knowing when a
# rule that looks sufficient turns out not to be.

# ---------------------------------------------------------------------------
# The wire format. This is the rule the whole commit exists for.
# ---------------------------------------------------------------------------
#
# Moshi reads these classes reflectively — they are plain Java with public fields and no
# @JsonClass annotation, because Moshi's codegen needs java.lang.Record reflection that Android's
# runtime does not provide. Field names ARE the JSON contract.
#
# Moshi's own consumer rules do not help: they keep @JsonClass enums, @FromJson/@ToJson methods
# and qualifiers, none of which applies here. Retrofit's rules keep the DTO classes as method
# return types but with allowobfuscation, so the class survives while its fields are renamed.
#
# Without this, R8 renames accessToken to something like `a`, Moshi looks for a field named
# accessToken, finds none, and every response silently parses as nulls. Nothing throws at the
# point of failure.
-keep class dev.vsdeadshot.flashcards.data.remote.dto.** { *; }

# ---------------------------------------------------------------------------
# View models
# ---------------------------------------------------------------------------
#
# ViewModelProvider locates the (Application) constructor reflectively, and
# lifecycle-viewmodel is the one dependency here that ships no consumer rules at all. Stripping
# the constructor turns every screen into an InstantiationException at first use.
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Fragments need no rule here. AAPT2 generates keep rules from resources, and that provably
# includes android:name on a navigation <fragment>: the generated aapt_rules.txt for this build
# contains an explicit -keep for all five of this app's fragments plus MainActivity. Checked in
# build/intermediates rather than assumed, and a hand-written rule would have duplicated it.

# ---------------------------------------------------------------------------
# The worker
# ---------------------------------------------------------------------------
#
# WorkManager stores a worker's class name in its database and reconstructs it reflectively, so
# a rename breaks a sync scheduled by a previous install rather than one scheduled now — the
# failure arrives after an upgrade, not in testing.
-keep class dev.vsdeadshot.flashcards.data.sync.SyncWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Room entities and DAOs need no rule either, and this was tested rather than reasoned about.
# Room generates real Java that reads fields directly, so R8 renames the generated accessor and
# the field together and stays consistent; the reflective part — loading FlashcardsDatabase_Impl
# by name — is already covered by room-runtime's own consumer rules. A -keep here was carried
# through the first verified build and then removed, and the release APK was re-verified without
# it: cache reads, writes, the outbox and a review all still work.
