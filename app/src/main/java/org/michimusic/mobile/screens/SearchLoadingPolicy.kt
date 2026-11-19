package org.michimusic.mobile.screens

/** An empty query must never display an indefinite loading spinner for an unready SAF root.
 * The library failure state remains independently authoritative and is never hidden by this policy.
 */
internal fun searchCatalogNeedsLoading(query: String, playlistFilter: Boolean): Boolean =
    !playlistFilter && normalizedSearchText(query).length >= 2
