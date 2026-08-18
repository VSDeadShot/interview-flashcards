package dev.vsdeadshot.flashcards.data.remote;

import dev.vsdeadshot.flashcards.data.remote.dto.CardDto;
import dev.vsdeadshot.flashcards.data.remote.dto.CardRequestDto;
import dev.vsdeadshot.flashcards.data.remote.dto.CreateTopicRequestDto;
import dev.vsdeadshot.flashcards.data.remote.dto.GenerateRequestDto;
import dev.vsdeadshot.flashcards.data.remote.dto.GenerateResponseDto;
import dev.vsdeadshot.flashcards.data.remote.dto.ReviewRequestDto;
import dev.vsdeadshot.flashcards.data.remote.dto.StatsDto;
import dev.vsdeadshot.flashcards.data.remote.dto.TopicDto;
import java.util.List;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.DELETE;
import retrofit2.http.GET;
import retrofit2.http.Headers;
import retrofit2.http.POST;
import retrofit2.http.PUT;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * Every endpoint in {@code docs/api-contract.md}, in the order that document lists them.
 *
 * <p>One interface rather than one per controller. There are nine methods against a single
 * server, and splitting them by the class that happens to serve each would describe the
 * backend's packages rather than the contract.
 *
 * <p>Calls are synchronous — {@link Call#execute()} on a background thread, not
 * {@link Call#enqueue}. The sync engine drains an ordered outbox and must know whether one
 * request succeeded before deciding to send the next; callbacks would turn that loop inside
 * out for no gain, since WorkManager has already given it a thread to block.
 */
public interface FlashcardsApi {

    @GET("topics")
    Call<List<TopicDto>> topics();

    @POST("topics")
    Call<TopicDto> createTopic(@Body CreateTopicRequestDto body);

    /**
     * @param includeArchived the sync passes {@code true}. A card that simply stopped appearing
     *     in a listing would be indistinguishable from one that was archived, and the cache
     *     needs to tell those apart to know whether to drop its copy or mark it retired.
     */
    @GET("cards")
    Call<List<CardDto>> cards(
            @Query("topicId") Long topicId, @Query("includeArchived") Boolean includeArchived);

    @POST("cards")
    Call<CardDto> createCard(@Body CardRequestDto body);

    @PUT("cards/{id}")
    Call<CardDto> updateCard(@Path("id") long id, @Body CardRequestDto body);

    /** Archives; the server never hard-deletes, so history survives. */
    @DELETE("cards/{id}")
    Call<Void> archiveCard(@Path("id") long id);

    @GET("study/queue")
    Call<List<CardDto>> queue(@Query("limit") Integer limit);

    @POST("study/{cardId}/review")
    Call<CardDto> review(@Path("cardId") long cardId, @Body ReviewRequestDto body);

    @GET("stats")
    Call<StatsDto> stats();

    /**
     * The one call here that can take a minute, because a model is writing while it waits.
     *
     * <p>The header is consumed by {@link TimeoutInterceptor} and never reaches the server. Sixty
     * seconds sits above the backend's own forty-five second upstream timeout, so the server gives
     * up first and this client is told 503 rather than left guessing what a socket timeout meant.
     */
    @Headers("X-Read-Timeout-Seconds: 60")
    @POST("cards/generate")
    Call<GenerateResponseDto> generate(@Body GenerateRequestDto body);
}
