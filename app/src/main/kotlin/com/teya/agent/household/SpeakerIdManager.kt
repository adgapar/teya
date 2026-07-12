package com.teya.agent.household

import android.content.Context
import android.util.Log
import com.teya.agent.safety.TeyaDatabase
import com.teya.agent.voice.speaker.CamPlusPlusSpeakerEmbedder
import com.teya.agent.voice.speaker.SpeakerEmbedder
import java.io.Closeable

/**
 * A candidate speaker guess: [member]'s enrolled voice was the closest match, at cosine [score].
 * [confident] means the score cleared the *higher* bar (`speakerIdConfidentThreshold`), not just
 * the base match threshold — see [HouseholdManager.speakerContextBlock] for how that distinction
 * changes what Teya's allowed to do with it (silent disambiguation only vs. actually usable, e.g.
 * to greet the person by name).
 */
data class SpeakerMatch(val member: Member, val score: Float, val confident: Boolean)

/**
 * Guesses which household member is likely speaking, from a raw audio window — either the
 * wake-word-time pre-roll ([WakeWordEngine]'s capture buffer) or, for a live re-check mid
 * conversation, the actual command audio just captured for STT (see
 * `VoicePipeline.consumeLastCommandAudio`) — the latter is usually a better sample: longer, more
 * natural conversational speech, VAD-trimmed, vs. just the wake phrase itself.
 *
 * A **soft, unconfirmed signal** for disambiguating shared aliases ("Dad" = two people) — never
 * authoritative, unlike [HouseholdManager.profileContextBlock]. See `docs/roadmap.md` → Household
 * setup & personalization.
 *
 * Depends only on the [SpeakerEmbedder] interface, not [CamPlusPlusSpeakerEmbedder] directly, so
 * the concrete embedding backend can be swapped later without touching this class.
 */
class SpeakerIdManager(
    context: Context,
    private val embedder: SpeakerEmbedder = CamPlusPlusSpeakerEmbedder(context),
) : Closeable {
    private val voiceSampleDao = TeyaDatabase.get(context).voiceSampleDao()

    /**
     * Best-guess [SpeakerMatch] for [pcm], or null if nobody enrolled clears [threshold]. Compares
     * against every enrolled sample (not one averaged vector per member — see [VoiceSample]'s doc
     * comment) and takes the best single-sample match **per member**, so one good sample outweighs
     * a noisy one — then the overall winner across members.
     */
    suspend fun identify(pcm: ShortArray, members: List<Member>, threshold: Float, confidentThreshold: Float): SpeakerMatch? {
        val samples = voiceSampleDao.getAll()
        if (samples.isEmpty()) {
            Log.d(TAG, "identify: no enrolled voice samples")
            return null
        }

        val query = embedder.embed(pcm)
        // Best score per lookupKey (not just the overall best) — logged in full below so a "why did
        // it pick X over Y" question is answerable from logs alone, not just the winner's number.
        val bestPerMember = LinkedHashMap<String, Float>()
        for (sample in samples) {
            val score = cosine(query, sample.embedding.toFloatArray())
            val prior = bestPerMember[sample.lookupKey]
            if (prior == null || score > prior) bestPerMember[sample.lookupKey] = score
        }
        val (bestKey, bestScore) = bestPerMember.maxByOrNull { it.value }?.toPair() ?: (null to -1f)
        val bestMember = bestKey?.let { key -> members.firstOrNull { it.lookupKey == key } }
        val matched = bestScore >= threshold
        val confident = matched && bestScore >= confidentThreshold

        // Live-tuning diagnostic — same pattern as WakeWordEngine's "peak wake score" and
        // VoicePipeline's "peak VAD confidence": these thresholds need real household data to tune.
        val perMemberScores = bestPerMember.entries.joinToString(", ") { (key, score) ->
            val name = members.firstOrNull { it.lookupKey == key }?.displayName ?: key
            "$name=${"%.3f".format(score)}"
        }
        Log.d(
            TAG,
            "identify: ${pcm.size} samples (${"%.2f".format(pcm.size / 16000f)}s) — [$perMemberScores] " +
                "best=${bestMember?.displayName ?: "none"} threshold=$threshold confidentThreshold=$confidentThreshold " +
                "matched=$matched confident=$confident",
        )
        return if (matched && bestMember != null) SpeakerMatch(bestMember, bestScore, confident) else null
    }

    override fun close() {
        embedder.close()
    }

    private companion object {
        const val TAG = "SpeakerIdManager"
    }
}
