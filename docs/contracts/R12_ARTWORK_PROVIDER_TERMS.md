# Artwork provider terms, honoured in code

Michi Music Mobile federates open artwork sources. Two of them publish images without
stating a licence, and one of those grants API access on written conditions. This note
records each condition and the code that satisfies it, so a violation is a review
finding rather than a matter of good intentions.

Applies to: `TheAudioDB`, `fanart.tv`. Openverse is governed by its own licence metadata,
which the picker verifies per image before anything is saved.

## fanart.tv

Source of the conditions: the General Terms and Commercial Use sections published by
fanart.tv, plus the API documentation at `api.fanart.tv`.

| Condition | How the code honours it |
|---|---|
| Inform your users of this website and the images you use | `ArtistArtworkPickerUiState.consentText` names fanart.tv and says its photographs declare no licence; `ArtworkProviderKeys` disclosure repeats where the images come from; the coverage row reads «Fanart.tv · sólo revisión manual»; the README states it. |
| Allow users to input their own API key, sent **in addition to** your project key | `FanartTvKeySettings` stores both keys; `FanartTvProvider` sends `api-key` and, when present, `client-key` next to it. A personal key never substitutes for the project key: without a project key the provider answers `Disabled` and makes no request. |
| Do not use our API methods embedded in your own API for third party use | The device calls `webservice.fanart.tv` directly. No Michi server proxies, re-signs or relays fanart.tv responses. |
| Do not use our API for commercial use without written consent | Michi Music Mobile is GPL-3.0 with no paid tier, no advertising and no resale of artwork. |
| Do not perform more requests than are necessary; no downloading all content | One request per explicit manual search, for one artist only (`FanartTvProvider.search` performs a single `GET /v3.2/music/{mbid}`). Candidates are capped at three thumbnails and three backgrounds. The provider is absent from the automatic ladder, so no background job, backfill or list scroll can reach it. |
| Do not directly access our data without using the documented API methods | Only the documented `GET /v3.2/music/{mbid}` endpoint is used. No HTML page is scraped and no undocumented path is called. |
| Keep the account email current | Operational obligation of the key holder; recorded here because it cannot be enforced by code. |

Rate limiting: `ArtworkHttp` already retries `429`/`503` honouring `Retry-After`, with at
most two retries, which is the polite behaviour the terms ask for.

## TheAudioDB

TheAudioDB's public API states no licence, no author and no copyright for the images it
returns. There is therefore no rights statement to verify, and the only honest options are
to omit the provider or to present its images as what they are.

Chosen: the second. `TheAudioDB` is manual-only, every candidate is `REQUIRES_REVIEW`, and
a saved image keeps `ArtworkRights.UNDECLARED_LICENSE` for ever. The build ships with
TheAudioDB's public test key (`123`), which is shared and rate limited; it is documented as
a floor for device builds, not a production credential.

## Why "no licence stated" cannot become "verified"

`allowedArtworkCandidate` accepts `UNDECLARED_LICENSE` only from `THEAUDIODB` or
`FANART_TV`, and only when `licenseId`, `licenseUrl` and `attribution` are all null. A
candidate that invents an identifier is rejected at the gate, so a fabricated licence
cannot travel into a saved selection and be rendered as if somebody had verified it.
The rule is covered by `TheAudioDbProviderTest` and `FanartTvProviderTest`.
