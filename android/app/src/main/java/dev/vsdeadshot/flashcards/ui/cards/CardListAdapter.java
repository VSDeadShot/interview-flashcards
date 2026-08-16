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
import dev.vsdeadshot.flashcards.data.local.CardSummaryRow;
import java.util.Objects;

/** The rows of the card list. */
public final class CardListAdapter
        extends ListAdapter<CardSummaryRow, CardListAdapter.CardViewHolder> {

    /**
     * Written out field by field rather than leaning on {@code equals}.
     *
     * <p>{@link CardSummaryRow} is a Room-constructed plain class with no {@code equals}, and
     * giving it one for the benefit of a list would put a definition of card equality somewhere
     * nothing else in the app agrees with — the entities already treat two rows as equal on id
     * alone. What matters here is narrower: whether the four things this row draws have changed.
     */
    private static final DiffUtil.ItemCallback<CardSummaryRow> DIFF =
            new DiffUtil.ItemCallback<>() {
                @Override
                public boolean areItemsTheSame(
                        @NonNull CardSummaryRow before, @NonNull CardSummaryRow after) {
                    return before.id == after.id;
                }

                @Override
                public boolean areContentsTheSame(
                        @NonNull CardSummaryRow before, @NonNull CardSummaryRow after) {
                    return Objects.equals(before.front, after.front)
                            && Objects.equals(before.topicName, after.topicName)
                            && before.rejected() == after.rejected()
                            && before.unsent() == after.unsent();
                }
            };

    /** What a tapped row does. Given rather than assumed, so the adapter owns no navigation. */
    public interface OnCardTapped {
        void onCardTapped(long localId);
    }

    private final OnCardTapped onCardTapped;

    public CardListAdapter(OnCardTapped onCardTapped) {
        super(DIFF);
        this.onCardTapped = onCardTapped;
    }

    @NonNull
    @Override
    public CardViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new CardViewHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_card, parent, false), onCardTapped);
    }

    @Override
    public void onBindViewHolder(@NonNull CardViewHolder holder, int position) {
        holder.bind(getItem(position));
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
