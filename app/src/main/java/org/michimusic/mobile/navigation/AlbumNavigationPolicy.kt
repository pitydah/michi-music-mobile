package org.michimusic.mobile.navigation

/** The same album destination retains its actual origin in NavController's back stack. */
fun albumDetailBackLabel(previousRoute: String?): String =
    if (previousRoute?.startsWith("artist/") == true) "Volver a artista" else "Volver a álbumes"
