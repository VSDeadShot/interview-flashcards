package dev.vsdeadshot.flashcards.ui.cards;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.color.MaterialColors;
import dev.vsdeadshot.flashcards.R;
import dev.vsdeadshot.flashcards.data.local.CandidateEntity;
import dev.vsdeadshot.flashcards.data.local.CardSummaryRow;
import java.util.Objects;

/**
 * The rows of the card list: the generated band, then the deck.
 *
 * <p>Three view types in one adapter rather than a {@code ConcatAdapter}. The band and the list
 * scroll as one thing and share a {@code RecyclerView}, and a concat would make the header's
 * count depend on a second adapter's state — two objects that have to agree, which is the shape
 * that eventually disagrees.
 */
public final class CardListAdapter
        extends ListAdapter<CardListItem, RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_CANDIDATE_HEADER = 0;
    private static final int VIEW_TYPE_CANDIDATE = 1;
    private static final int VIEW_TYPE_CARD = 2;

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
        void onCardTapped(long localId);
    }

    /** What the band offers: open one to correct it, or decide about it where it sits. */
    public interface OnCandidateDecision {
        void onOpen(long candidateId);

        void onAccept(long candidateId);

        void onDiscard(long candidateId);

        void onDiscardAll();
    }

    private final OnCardTapped onCardTapped;
    private final OnCandidateDecision onCandidateDecision;

    public CardListAdapter(OnCardTapped onCardTapped, OnCandidateDecision onCandidateDecision) {
        super(DIFF);
        this.onCardTapped = onCardTapped;
        this.onCandidateDecision = onCandidateDecision;
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
            ((CandidateViewHolder) holder).bind(candidate.candidate());
        } else {
            ((CardViewHolder) holder).bind(((CardListItem.Card) item).card());
        }
    }

    static final class HeaderViewHolder extends RecyclerView.ViewHolder {

        private final TextView text;

        HeaderViewHolder(@NonNull View row, OnCandidateDecision decisions) {
            super(row);
            text = row.findViewById(R.id.candidate_header_text);
            row.findViewById(R.id.candidate_discard_all)
                    .setOnClickListener(tapped -> decisions.onDiscardAll());
        }

        void bind(int count) {
            text.setText(text.getResources()
                    .getQuantityString(R.plurals.candidate_header, count, count));
        }
    }

    static final class CandidateViewHolder extends RecyclerView.ViewHolder {

        private final TextView front;
        private final TextView back;
        private final View accept;
        private final View discard;

        private final OnCandidateDecision decisions;

        CandidateViewHolder(@NonNull View row, OnCandidateDecision decisions) {
            super(row);
            this.decisions = decisions;
            front = row.findViewById(R.id.candidate_front);
            back = row.findViewById(R.id.candidate_back);
            accept = row.findViewById(R.id.candidate_accept);
            discard = row.findViewById(R.id.candidate_discard);
        }

        void bind(CandidateEntity candidate) {
            front.setText(candidate.front);
            back.setText(candidate.back);
            // Listeners are bound per candidate rather than once in the constructor: a recycled
            // holder would otherwise still be pointing at the row it last drew, and accepting
            // would add a card the user never looked at.
            accept.setOnClickListener(tapped -> decisions.onAccept(candidate.id));
            discard.setOnClickListener(tapped -> decisions.onDiscard(candidate.id));
            itemView.setOnClickListener(tapped -> decisions.onOpen(candidate.id));
        }
    }

    static final class CardViewHolder extends RecyclerView.ViewHolder {

        private final TextView front;
        private final TextView topic;
        private final TextView status;

        private final OnCardTapped onCardTapped;

        CardViewHolder(@NonNull View row, OnCardTapped onCardTapped) {
            super(row);
            this.onCardTapped = onCardTapped;
            front = row.findViewById(R.id.card_front);
            topic = row.findViewById(R.id.card_topic);
            status = row.findViewById(R.id.card_status);
        }

        void bind(CardSummaryRow card) {
            itemView.setOnClickListener(tapped -> onCardTapped.onCardTapped(card.id));
            front.setText(card.front);
            // A card outlives its topic being deleted on the server, so the row has to say
            // something. Blank would read as a row that failed to load.
            topic.setText(card.topicName != null
                    ? card.topicName
                    : topic.getContext().getString(R.string.cards_no_topic));

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
    }
}
