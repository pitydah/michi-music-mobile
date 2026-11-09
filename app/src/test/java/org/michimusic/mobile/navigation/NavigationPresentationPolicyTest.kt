package org.michimusic.mobile.navigation
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
class NavigationPresentationPolicyTest {
 @Test fun queueOpensCollectionNotAnInventedPlaylistId() {
  assertEquals("playlist",queuePlaylistsDestination())
  assertEquals(MichiScreenLevel.PRIMARY,navigationPresentationFor(queuePlaylistsDestination()).level)
 }
 @Test fun primaryDestinationsKeepCorrectDockTab() {
  listOf("home","library","playlist","search","devices").forEach {route->
   val p=navigationPresentationFor(route)
   assertEquals(route,p.selectedPrimaryTab)
   assertEquals(MichiScreenLevel.PRIMARY,p.level)
   assertTrue(p.showDock);assertTrue(p.showMiniPlayer)
  }
 }
 @Test fun playlistDetailsKeepPlaylistsSelectedAndQueueRemainsLibrary(){
  listOf("playlist/{playlistId}","playlist/my-id").forEach { route ->
   val p=navigationPresentationFor(route)
   assertEquals("playlist",p.selectedPrimaryTab)
   assertEquals(MichiScreenLevel.DETAIL,p.level)
  }
  assertEquals("library",navigationPresentationFor("queue").selectedPrimaryTab)
 }
 @Test fun ecosystemRoutesRemainUnderDevices(){
  listOf("remote","synced","sync","audio-route").forEach {route->
   assertEquals("devices",navigationPresentationFor(route).selectedPrimaryTab)
  }
 }
 @Test fun settingsAndDiagnosticsDoNotClaimHomeTab(){
  assertEquals(null,navigationPresentationFor("settings").selectedPrimaryTab)
  assertEquals(null,navigationPresentationFor("diagnostics").selectedPrimaryTab)
 }
 @Test fun nowPlayingIsImmersiveWithoutDuplicatedMini(){
  val p=navigationPresentationFor("nowplaying")
  assertEquals(MichiScreenLevel.IMMERSIVE,p.level)
  assertFalse(p.showDock);assertFalse(p.showMiniPlayer)
  assertEquals(null,p.selectedPrimaryTab)
 }
 @Test fun wizardRoutesAreImmersiveButBelongToDevices(){
  listOf("sync_wizard/{deviceId}","sync_wizard/abc123").forEach {
   val p=navigationPresentationFor(it)
   assertEquals(MichiScreenLevel.IMMERSIVE,p.level)
   assertFalse(p.showDock);assertFalse(p.showMiniPlayer)
   assertEquals("devices",p.selectedPrimaryTab)
  }
 }
 @Test fun unknownDestinationsDoNotPretendToBeHome(){
  val p=navigationPresentationFor("future_screen")
  assertEquals(null,p.selectedPrimaryTab)
  assertEquals(MichiScreenLevel.DETAIL,p.level)
  assertTrue(p.showDock)
 }
 @Test fun nullableStartupHasNoFalseSelectedTab(){
  assertEquals(null,navigationPresentationFor(null).selectedPrimaryTab)
 }
}
