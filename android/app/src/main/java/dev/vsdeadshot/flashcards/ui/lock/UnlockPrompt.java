package dev.vsdeadshot.flashcards.ui.lock;

import android.content.Context;
import android.os.Build;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricManager.Authenticators;
import androidx.biometric.BiometricPrompt;
import androidx.fragment.app.FragmentActivity;
import dev.vsdeadshot.flashcards.R;
import java.util.concurrent.Executor;

/**
 * The platform's own unlock dialog, configured once.
 *
 * <p>Here rather than inline in the activity because the configuration has a version branch in
 * it, and a branch on {@code Build.VERSION} is exactly the kind of thing that gets copied
 * slightly differently the second time it is needed.
 */
public final class UnlockPrompt {

    /**
     * {@code BIOMETRIC_WEAK} rather than {@code BIOMETRIC_STRONG}.
     *
     * <p>Strong is what a Keystore-bound key requires, and there is no key here by design. What
     * is left is a gate on the interface, where insisting on strong would exclude perfectly
     * reasonable face unlock on a good many devices and buy nothing this design can spend.
     */
    private static final int BIOMETRIC = Authenticators.BIOMETRIC_WEAK;

    /**
     * Non-null only in tests; see {@link #forceAvailable}.
     */
    @Nullable
    private static volatile Boolean forcedAvailability;

    private UnlockPrompt() {
    }

    /**
     * Whether this device can satisfy the gate at all.
     *
     * <p>Checked before the setting is allowed on, so that switching it on cannot be the thing
     * that shuts somebody out. A phone with no fingerprint enrolled and no screen lock has no
     * way past a prompt.
     */
    public static boolean available(Context context) {
        Boolean forced = forcedAvailability;
        if (forced != null) {
            return forced;
        }
        return BiometricManager.from(context).canAuthenticate(allowed())
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    /**
     * Overrides what {@link #available} reports, for tests only.
     *
     * <p>Needed because Robolectric answers {@code BIOMETRIC_SUCCESS} to every
     * {@code canAuthenticate} call regardless of what it is asked — it does not model a device
     * without a sensor or an enrolment. Verified rather than assumed: a probe printed
     * {@code 0} for weak, strong, credential and the combination alike. So the one case worth
     * testing here, a device that cannot satisfy the gate, is unreachable without a seam.
     *
     * <p>Null puts the real check back. Production never calls this.
     */
    @VisibleForTesting
    public static void forceAvailable(@Nullable Boolean available) {
        forcedAvailability = available;
    }

    public static BiometricPrompt build(FragmentActivity activity, Executor executor,
            BiometricPrompt.AuthenticationCallback callback) {
        return new BiometricPrompt(activity, executor, callback);
    }

    public static BiometricPrompt.PromptInfo info(Context context) {
        BiometricPrompt.PromptInfo.Builder builder = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(context.getString(R.string.lock_title))
                .setSubtitle(context.getString(R.string.lock_subtitle));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(allowed());
        } else {
            // setAllowedAuthenticators with DEVICE_CREDENTIAL is only honoured from API 30.
            // Below it, this deprecated call is the supported route to the same behaviour, and
            // the two are mutually exclusive with a negative button — which is why there is no
            // setNegativeButtonText on either branch.
            builder.setDeviceCredentialAllowed(true);
        }
        return builder.build();
    }

    /**
     * A biometric <em>or</em> the device's own PIN, pattern or password.
     *
     * <p>The credential fallback is not a convenience. Without it, a finger the sensor stops
     * recognising — a cut, a wet hand, a phone whose enrolment was cleared — would leave somebody
     * locked out of their own cards with reinstalling as the only way back in. A lock on a
     * study app must never be harder to get past than the phone it runs on.
     */
    private static int allowed() {
        return BIOMETRIC | Authenticators.DEVICE_CREDENTIAL;
    }
}
