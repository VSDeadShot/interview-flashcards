package dev.vsdeadshot.flashcards.ui.auth;

import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.auth.TokenStore.SignedOutReason;
import dev.vsdeadshot.flashcards.ui.Motion;
import dev.vsdeadshot.flashcards.ui.auth.AuthViewModel.SignInState;

/**
 * Where the passphrase is typed.
 *
 * <p><strong>A destination, not a gate.</strong> Nothing in this app waits for it: every screen
 * reads the cache, so somebody with a deck already on the device studies, writes, edits and
 * archives without a token, and only syncing needs one. Putting this in front of the app would
 * have traded the offline-first promise for a login form — the one thing the whole cache design
 * exists to avoid.
 *
 * <p>It leaves by going back rather than forward. Signing in is something a person came here to
 * do and then returns from; navigating on to a screen of this fragment's choosing would take
 * over a back stack it does not own.
 */
public final class SignInFragment extends Fragment {

    public SignInFragment() {
        super(R.layout.fragment_sign_in);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Motion.peerDestination(this);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // Scoped to the activity, so the toolbar that sent somebody here and this screen are
        // reading one instance. Signing in successfully has to reach the toolbar, and this
        // fragment is gone by the time it would.
        AuthViewModel model =
                new ViewModelProvider(requireActivity()).get(AuthViewModel.class);

        TextInputEditText passphrase = view.findViewById(R.id.sign_in_passphrase);
        MaterialButton submit = view.findViewById(R.id.sign_in_submit);
        Motion.press(submit);

        submit.setOnClickListener(tapped -> model.signIn(text(passphrase)));
        // The keyboard's own done key does the same thing. The field is the only input on the
        // screen, so reaching past the keyboard for a button below it is the long way round.
        passphrase.setOnEditorActionListener((field, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                model.signIn(text(passphrase));
                return true;
            }
            return false;
        });

        model.state().observe(getViewLifecycleOwner(), state -> {
            TextView explanation = view.findViewById(R.id.sign_in_explanation);
            explanation.setText(explain(state.signedIn(), state.reason()));
        });

        model.signInState().observe(getViewLifecycleOwner(), state -> draw(view, model, state));
    }

    /**
     * Why this screen is on. An expired session is the one worth saying out loud: the person did
     * not do anything, their reviews have been queuing up unsent, and nothing else in the app
     * would tell them.
     */
    private int explain(boolean signedIn, SignedOutReason reason) {
        if (signedIn) {
            return R.string.auth_signed_in;
        }
        return reason == SignedOutReason.SESSION_EXPIRED
                ? R.string.auth_expired
                : R.string.auth_explanation;
    }

    private void draw(@NonNull View view, @NonNull AuthViewModel model,
            @NonNull SignInState state) {
        view.findViewById(R.id.sign_in_progress)
                .setVisibility(state.running() ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.sign_in_submit).setEnabled(!state.running());

        TextView error = view.findViewById(R.id.sign_in_error);
        if (state.error() == null) {
            error.setVisibility(View.GONE);
        } else {
            error.setText(state.error());
            error.setVisibility(View.VISIBLE);
        }

        if (state.succeeded()) {
            // Cleared before leaving, so coming back later does not open on the outcome of an
            // attempt made a week ago.
            model.clearSignInState();
            NavHostFragment.findNavController(this).popBackStack();
        }
    }

    private static String text(TextInputEditText field) {
        return field.getText() == null ? "" : field.getText().toString();
    }
}
