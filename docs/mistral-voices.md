# Mistral (Voxtral) TTS Voices

Catalog of the built-in TTS voices, for the TTS `voice` field and Admin's voice picker
(`ui/admin/AdminComposables.kt`'s `VoicePicker`, backed by `brain/MistralVoices.kt`).

- **Source (live):** `GET https://api.mistral.ai/v1/audio/voices?limit=100` (Bearer auth). This is the
  authoritative list — `MistralVoices.kt` hardcodes it instead (fixed catalog, no network dependency
  for Admin); re-sync manually if Mistral adds/changes voices.
- **TTS request:** `POST /v1/audio/speech` with `{"model":"voxtral-mini-tts-latest","input":"…","voice":"<slug or id>","response_format":"mp3"}`.
- **`voice` accepts either the `slug` or the `id`** (both verified working). We use the slug for readability.
- **Total:** 30 voices = 4 "actors" × emotion variants, across en-US, en-GB, fr-FR.
- **Current app default:** `fr_marie_happy` (Marie – Happy) — `MistralVoices.DEFAULT`, used by
  `ConfigManager.ttsVoice` until changed in Admin's Voice section.

> Note: there is no gender-neutral voice; each actor is male or female. Teya's chosen voice is
> **Marie – Happy** (fr-FR, "warm, radiant") — a warm French-accented female reading English, which
> fits the "warm, belongs to the family" brand. English-native alternatives if the accent grates:
> `gb_jane_neutral` (clear, measured) or `gb_jane_confident` (assured, poised).

## Jane — en-GB, female
| Emotion | slug | tags | id |
|---|---|---|---|
| Neutral | `gb_jane_neutral` | clear, measured, neutral | `82c99ee6-f932-423f-a4a3-d403c8914b8d` |
| Confident | `gb_jane_confident` | assured, poised, confident | `cbe96cf0-85ec-4a10-accb-0b35c93b6dfd` |
| Curious | `gb_jane_curious` | inquisitive, open, curious | `5de47977-6e47-4266-a938-3bc1d76b4676` |
| Sad | `gb_jane_sad` | soft, subdued, sad | `c7a8eb83-5247-4540-89f3-6650d349100d` |
| Frustrated | `gb_jane_frustrated` | tense, clipped, frustrated | `60844938-221d-4d1e-8233-34203f787d9f` |
| Confused | `gb_jane_confused` | hesitant, uncertain, confused | `7d0a90a3-c211-4489-aaa0-61269299edc7` |
| Jealousy | `gb_jane_jealousy` | bitter, strained, jealous | `e7168caa-f7ed-4e1c-98a1-434251f4f2b0` |
| Shameful | `gb_jane_shameful` | quiet, remorseful, ashamed | `230ccacf-8800-4aa0-8ac2-8d004f1d9fb7` |
| Sarcasm | `gb_jane_sarcasm` | dry, wry, sarcastic | `a3e41ea8-020b-44c0-8d8b-f6cc03524e31` |

## Marie — fr-FR, female
| Emotion | slug | tags | id |
|---|---|---|---|
| Neutral | `fr_marie_neutral` | composed, steady, neutral | `5a271406-039d-46fe-835b-fbbb00eaf08d` |
| **Happy** ⭐ default | `fr_marie_happy` | warm, radiant, happy | `49d024dd-981b-4462-bb17-74d381eb8fd7` |
| Excited | `fr_marie_excited` | vibrant, bubbly, excited | `2f62b1af-aea3-4079-9d10-7ca665ee7243` |
| Curious | `fr_marie_curious` | bright, probing, curious | `e0580ce5-e63c-4cbe-88c8-a983b80c5f1f` |
| Sad | `fr_marie_sad` | muted, heavy, sad | `4adeb2c6-25a3-44bc-8100-5234dfc1193b` |
| Angry | `fr_marie_angry` | fierce, sharp, angry | `a7c07cdc-1c35-4d87-a938-c610a654f600` |

## Paul — en-US, male
| Emotion | slug | tags | id |
|---|---|---|---|
| Neutral | `en_paul_neutral` | relaxed, balanced, neutral | `c69964a6-ab8b-4f8a-9465-ec0925096ec8` |
| Happy | `en_paul_happy` | sunny, easygoing, happy | `1024d823-a11e-43ee-bf3d-d440dccc0577` |
| Cheerful | `en_paul_cheerful` | upbeat, breezy, cheerful | `01d985cd-5e0c-4457-bfd8-80ba31a5bc03` |
| Confident | `en_paul_confident` | bold, punchy, confident | `98559b22-62b5-4a64-a7cd-fc78ca41faa8` |
| Excited | `en_paul_excited` | bouncy, spirited, excited | `5940190b-f58a-4c3e-8264-a40d63fd6883` |
| Frustrated | `en_paul_frustrated` | edgy, snappy, frustrated | `1f017bcb-02e5-460d-989b-db065c0c6122` |
| Sad | `en_paul_sad` | heavy, hushed, sad | `530e2e20-58e2-45d8-b0a5-4594f4915944` |
| Angry | `en_paul_angry` | raw, gruff, angry | `cb891218-482c-4392-9878-91e8d999d57a` |

## Oliver — en-GB, male
| Emotion | slug | tags | id |
|---|---|---|---|
| Neutral | `gb_oliver_neutral` | calm, even, neutral | `e3596645-b1af-469e-b857-f18ddedc7652` |
| Cheerful | `gb_oliver_cheerful` | bright, lively, cheerful | `5ad5d44e-6b4e-4a57-a8a8-4cae088034ed` |
| Confident | `gb_oliver_confident` | firm, decisive, confident | `8169ab87-bc99-4669-a5ec-6855860ace24` |
| Curious | `gb_oliver_curious` | thoughtful, engaged, curious | `390c8a2b-60a6-4882-8437-c49a8bd33b63` |
| Excited | `gb_oliver_excited` | energetic, crisp, excited | `e8e5b1de-493c-4061-8414-e2170f9f4b6f` |
| Sad | `gb_oliver_sad` | low, hollow, sad | `d4101b8f-12c3-450d-a812-7d700b3a3245` |
| Angry | `gb_oliver_angry` | intense, forceful, angry | `862274a7-8333-48f7-b668-f19c932999e0` |
