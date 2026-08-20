package dev.vsdeadshot.flashcards.ui.cards;

import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.shape.ShapeAppearanceModel;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.local.CandidateEntity;
import dev.vsdeadshot.flashcards.data.local.CardSummaryRow;
import dev.vsdeadshot.flashcards.ui.Motion;
import java.util.Objects;

/**
 * The rows of the card list: the generated band, then the deck.
 *
 * <p>Three view types in one adapter rather than a {@code ConcatAdapter}. The band and the list
 * scroll as one thing and share a {@code RecyclerView}, and a concat would make the header's
 * count depend on a second adapter's state — two objects that have to agree, which is the shape
 * that eventually disagrees.
 *
 * <p><strong>The rounded surfaces belong to the groups, not to the rows.</strong> The design draws
 * the deck as one container with hairlines in it and the band as another above it, which no row
 * layout can express, because a row does not know where in the list it is. This adapter does, so
 * it paints each row's background on bind: rounded at the top of a group, rounded at the bottom,
 * square in between. That is also why neither item layout sets a background of its own — anything
 * they set would be replaced here.
 */
public final class CardListAdapter
        extends ListAdapter<CardListItem, RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_CANDIDATE_HEADER = 0;
    private static final int VIEW_TYPE_CANDIDATE = 1;
    private static final int VIEW_TYPE_CARD = 2;

    /** Material 3's extra-large corner, which is what every large container here is drawn at. */
    private static final float GROUP_CORNER_DP = 28f;

    /** The accepted candidate leaving downward, towards the list it is joining. */
    private static final long ACCEPT_MS = 240L;

    private static final float ACCEPT_DROP_DP = 16f;

    /** A discarded one leaving sideways instead. Same length, opposite direction, so the two
     * outcomes never look alike at a glance. */
    private static final long DISCARD_MS = 220L;

    private static final float DISCARD_SLIDE_DP = 48f;

    /**
     * How long the new row keeps its tint.
     *
     * <p>Slow on purpose. It is the only thing saying which of however many rows is the one just
     * added, and a list that has just grown is exactly when that is hard to find.
     */
    private static final long SETTLE_MS = 1000L;

    /**
     * Written out kind by kind rather than leaning on {@code equals}.
     *
     * <p>{@link CardSummaryRow} and {@link CandidateEntity} are Room-constructed plain classes
     * with no {@code equals}, and giving them one for the benefit of a list would put a
     * definition of card equality somewhere nothing else in the app agrees with — the entities
     * already treat two rows as equal on id alone. What matters here is narrower: whether the
     * things this row draws have changed.
     */
    private static final DiffUtil.ItemCallback<CardListItem> DIFF =
            new DiffUtil.ItemCallback<>() {
                @Override
                public boolean areItemsTheSame(
                        @NonNull CardListItem before, @NonNull CardListItem after) {
                    // Class first: a candidate id and a card id are separate sequences and can
                    // collide, so an id-only comparison would call two unrelated rows the same.
                    if (before.getClass() != after.getClass()) {
                        return false;
                    }
                    if (before instanceof CardListItem.Candidate candidate) {
                        return candidate.candidate().id
                                == ((CardListItem.Candidate) after).candidate().id;
                    }
                    if (before instanceof CardListItem.Card card) {
                        return card.card().id == ((CardListItem.Card) after).card().id;
                    }
                    // The header, of which there is only ever one, so it is always itself.
                    return true;
                }

                @Override
                public boolean areContentsTheSame(
                        @NonNull CardListItem before, @NonNull CardListItem after) {
                    if (before instanceof CardListItem.Candidate candidate) {
                        return sameContent(candidate.candidate(),
                                ((CardListItem.Candidate) after).candidate());
                    }
                    if (before instanceof CardListItem.Card card) {
                        return sameContent(card.card(), ((CardListItem.Card) after).card());
                    }
                    return ((CardListItem.Header) before).count()
                            == ((CardListItem.Header) after).count();
                }
            };

    private static boolean sameContent(CandidateEntity before, CandidateEntity after) {
        return Objects.equals(before.front, after.front)
                && Objects.equals(before.back, after.back);
    }

    private static boolean sameContent(CardSummaryRow before, CardSummaryRow after) {
        return Objects.equals(before.front, after.front)
                && Objects.equals(before.topicName, after.topicName)
                && before.rejected() == after.rejected()
                && before.unsent() == after.unsent();
    }

    /** What a tapped row does. Given rather than assumed, so the adapter owns no navigation. */
    public interface OnCardTapped {
        void onCardTapped(long localId, @NonNull View row);
    }

    /** What the band offers: open one to correct it, or decide about it where it sits. */
    public interface OnCandidateDecision {
        void onOpen(long candidateId, @NonNull View row);

        void onAccept(long candidateId);

        void onDiscard(long candidateId);

        void onDiscardAll();
    }

    private final OnCardTapped onCardTapped;
    private final OnCandidateDecision onCandidateDecision;

    /**
     * The card that has just been accepted, or null.
     *
     * <p>Held here rather than on the row, because it is not a property of the card: it is a
     * property of this moment, and it stops being true a second later.
     */
    @Nullable
    private Long justAdded;

    public CardListAdapter(OnCardTapped onCardTapped, OnCandidateDecision onCandidateDecision) {
        super(DIFF);
        this.onCardTapped = onCardTapped;
        this.onCandidateDecision = onCandidateDecision;
    }

    /** Names the row to tint once, on the next bind. Cleared as soon as it has been used. */
    public void highlightOnce(long localId) {
        justAdded = localId;
    }

    @Override
    public int getItemViewType(int position) {
        CardListItem item = getItem(position);
        if (item instanceof CardListItem.Header) {
            return VIEW_TYPE_CANDIDATE_HEADER;
        }
        return item instanceof CardListItem.Candidate ? VIEW_TYPE_CANDIDATE : VIEW_TYPE_CARD;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case VIEW_TYPE_CANDIDATE_HEADER:
                return new HeaderViewHolder(
                        inflater.inflate(R.layout.item_candidate_header, parent, false),
                        onCandidateDecision);
            case VIEW_TYPE_CANDIDATE:
                return new CandidateViewHolder(
                        inflater.inflate(R.layout.item_candidate, parent, false),
                        onCandidateDecision);
            default:
                return new CardViewHolder(
                        inflater.inflate(R.layout.item_card, parent, false), onCardTapped);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        CardListItem item = getItem(position);
        if (item instanceof CardListItem.Header header) {
            ((HeaderViewHolder) holder).bind(header.count());
        } else if (item instanceof CardListItem.Candidate candidate) {
            ((CandidateViewHolder) holder).bind(candidate.candidate(), isLastOfBand(position));
        } else {
            CardSummaryRow card = ((CardListItem.Card) item).card();
            boolean fresh = justAdded != null && justAdded == card.id;
            if (fresh) {
                justAdded = null;
            }
            ((CardViewHolder) holder).bind(
                    card, isFirstCard(position), isLastCard(position), fresh);
        }
    }

    private boolean isFirstCard(int position) {
        return position == 0 || !(getItem(position - 1) instanceof CardListItem.Card);
    }

    private boolean isLastCard(int position) {
        return position == getItemCount() - 1;
    }

    private boolean isLastOfBand(int position) {
        return position == getItemCount() - 1
                || !(getItem(position + 1) instanceof CardListItem.Candidate);
    }

    /**
     * Paints one row as part of a rounded group.
     *
     * <p>A shape drawable built here rather than four background files, because the four would be
     * the same shape written out four times and would have to name their fill in a colour
     * resource — this reads it from the theme, so the group follows the dark theme like
     * everything else.
     *
     * @return the fill, so a caller that wants to animate it has something to animate
     */
    private static MaterialShapeDrawable paintGroup(@NonNull View row, @AttrRes int fillAttr,
            boolean roundTop, boolean roundBottom, boolean rippled) {
        float corner = Motion.dp(row, GROUP_CORNER_DP);
        ShapeAppearanceModel shape = ShapeAppearanceModel.builder()
                .setTopLeftCornerSize(roundTop ? corner : 0f)
                .setTopRightCornerSize(roundTop ? corner : 0f)
                .setBottomLeftCornerSize(roundBottom ? corner : 0f)
                .setBottomRightCornerSize(roundBottom ? corner : 0f)
                .build();

        MaterialShapeDrawable fill = new MaterialShapeDrawable(shape);
        fill.setFillColor(ColorStateList.valueOf(MaterialColors.getColor(row, fillAttr)));
        if (!rippled) {
            row.setBackground(fill);
            return fill;
        }

        // Masked with the same shape, so the ripple stops at the rounded corner rather than
        // squaring off the top or bottom of the group as it spreads.
        MaterialShapeDrawable mask = new MaterialShapeDrawable(shape);
        row.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(MaterialColors.getColor(
                        row, androidx.appcompat.R.attr.colorControlHighlight)),
                fill,
                mask));
        return fill;
    }

    static final class HeaderViewHolder extends RecyclerView.ViewHolder {

        private final TextView text;

        HeaderViewHolder(@NonNull View row, OnCandidateDecision decisions) {
            super(row);
            text = row.findViewById(R.id.candidate_header_text);
            View discardAll = row.findViewById(R.id.candidate_discard_all);
            Motion.press(discardAll);
            discardAll.setOnClickListener(tapped -> decisions.onDiscardAll());
        }

        void bind(int count) {
            text.setText(text.getResources()
                    .getQuantityString(R.plurals.candidate_header, count, count));
            // The top of the band, always: nothing is ever above the header.
            paintGroup(itemView, com.google.android.material.R.attr.colorSecondaryContainer,
                    true, false, false);
        }
    }

    static final class CandidateViewHolder extends RecyclerView.ViewHolder {

        private final TextView front;
        private final TextView back;
        private final View card;
        private final View accept;
        private final View edit;
        private final View discard;

        private final OnCandidateDecision decisions;

        CandidateViewHolder(@NonNull View row, OnCandidateDecision decisions) {
            super(row);
            this.decisions = decisions;
            front = row.findViewById(R.id.candidate_front);
            back = row.findViewById(R.id.candidate_back);
            card = row.findViewById(R.id.candidate_card);
            accept = row.findViewById(R.id.candidate_accept);
            edit = row.findViewById(R.id.candidate_edit);
            discard = row.findViewById(R.id.candidate_discard);
            Motion.press(accept);
            Motion.press(edit);
            Motion.press(discard);
        }

        void bind(CandidateEntity candidate, boolean isLastOfBand) {
            front.setText(candidate.front);
            back.setText(candidate.back);
            // The band's tint, rounded at the bottom only on the row that ends it.
            paintGroup(itemView, com.google.android.material.R.attr.colorSecondaryContainer,
                    false, isLastOfBand, false);
            // A recycled holder may still be part way through leaving.
            card.setAlpha(1f);
            card.setTranslationX(0f);
            card.setTranslationY(0f);

            // Listeners are bound per candidate rather than once in the constructor: a recycled
            // holder would otherwise still be pointing at the row it last drew, and accepting
            // would add a card the user never looked at.
            accept.setOnClickListener(tapped -> leave(true, () ->
                    decisions.onAccept(candidate.id)));
            discard.setOnClickListener(tapped -> leave(false, () ->
                    decisions.onDiscard(candidate.id)));
            edit.setOnClickListener(tapped -> decisions.onOpen(candidate.id, card));
            card.setOnClickListener(tapped -> decisions.onOpen(candidate.id, card));
        }

        /**
         * Sends the row out before the decision is written.
         *
         * <p>Two directions for the two outcomes. An accepted candidate collapses downward,
         * towards the list it is joining; a discarded one goes sideways off the edge. Same
         * duration, opposite direction, so the two never look alike at a glance — which matters
         * because they are irreversible and adjacent.
         *
         * <p>The decision is made in the end action rather than up front, so the row is already
         * gone by the time the list is rebuilt around it.
         */
        private void leave(boolean accepted, @NonNull Runnable decide) {
            card.animate()
                    .alpha(0f)
                    .translationY(accepted ? Motion.dp(card, ACCEPT_DROP_DP) : 0f)
                    .translationX(accepted ? 0f : Motion.dp(card, DISCARD_SLIDE_DP))
                    .setDuration(accepted ? ACCEPT_MS : DISCARD_MS)
                    .setInterpolator(accepted ? Motion.FAST_OUT_SLOW_IN : Motion.FAST_OUT_LINEAR_IN)
                    .withEndAction(decide)
                    .start();
        }
    }

    static final class CardViewHolder extends RecyclerView.ViewHolder {

        private final TextView front;
        private final TextView topic;
        private final TextView status;
        private final View divider;

        private final OnCardTapped onCardTapped;

        CardViewHolder(@NonNull View row, OnCardTapped onCardTapped) {
            super(row);
            this.onCardTapped = onCardTapped;
            front = row.findViewById(R.id.card_front);
            topic = row.findViewById(R.id.card_topic);
            status = row.findViewById(R.id.card_status);
            divider = row.findViewById(R.id.card_divider);
        }

        void bind(CardSummaryRow card, boolean isFirst, boolean isLast, boolean fresh) {
            itemView.setOnClickListener(tapped -> onCardTapped.onCardTapped(card.id, itemView));
            front.setText(card.front);
            // A card outlives its topic being deleted on the server, so the row has to say
            // something. Blank would read as a row that failed to load.
            topic.setText(card.topicName != null
                    ? card.topicName
                    : topic.getContext().getString(R.string.cards_no_topic));
            // The container's own edge separates the last row from what is under it.
            divider.setVisibility(isLast ? View.GONE : View.VISIBLE);

            MaterialShapeDrawable fill = paintGroup(itemView,
                    com.google.android.material.R.attr.colorSurfaceContainerLowest,
                    isFirst, isLast, true);
            if (fresh) {
                settle(fill);
            }

            // Rejected before unsent, because a refused card is also unsent and only the refusal
            // is worth acting on: nothing will be sent again until the card is edited.
            if (card.rejected()) {
                status.setVisibility(View.VISIBLE);
                status.setText(R.string.cards_rejected);
                status.setTextColor(MaterialColors.getColor(
                        status, androidx.appcompat.R.attr.colorError));
            } else if (card.unsent()) {
                status.setVisibility(View.VISIBLE);
                status.setText(R.string.cards_unsent);
                status.setTextColor(MaterialColors.getColor(
                        status, com.google.android.material.R.attr.colorOnSurfaceVariant));
            } else {
                // Most rows. A badge saying "synced" on every one of them would be noise.
                status.setVisibility(View.GONE);
            }
        }

        /** The tint on a row that has just arrived, fading back to the surface it lives on. */
        private void settle(@NonNull MaterialShapeDrawable fill) {
            int from = MaterialColors.getColor(
                    itemView, com.google.android.material.R.attr.colorSecondaryContainer);
            int to = MaterialColors.getColor(
                    itemView, com.google.android.material.R.attr.colorSurfaceContainerLowest);
            ValueAnimator tint = ValueAnimator.ofArgb(from, to);
            tint.setDuration(SETTLE_MS);
            tint.setInterpolator(Motion.FAST_OUT_SLOW_IN);
            tint.addUpdateListener(frame -> fill.setFillColor(
                    ColorStateList.valueOf((int) frame.getAnimatedValue())));
            tint.start();
        }
    }
}
