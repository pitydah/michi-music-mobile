"""K44: real pure-Kotlin identity/provider policy tests plus patch wiring contracts.

No Android instrumentation or fictional provider hit-rate metrics.
"""
import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
APP = ROOT / 'app/src/main/java/org/michimusic/mobile/artist'
T = ROOT / 'app/src/test/java/org/michimusic/mobile/artist'
A = APP / 'artwork'
AT = T / 'artwork'

SHIM = 'package org.junit\n@Retention(AnnotationRetention.RUNTIME)\n@Target(AnnotationTarget.FUNCTION)\nannotation class Test\nobject Assert {\n fun assertEquals(expected:Any?, actual:Any?) { check(expected==actual) { "Expected $expected, got $actual" } }\n fun assertTrue(actual:Boolean) { check(actual) { "Expected true" } }\n fun assertFalse(actual:Boolean) { check(!actual) { "Expected false" } }\n fun assertNull(actual:Any?) { check(actual==null) { "Expected null, got $actual" } }\n}\n'
MODELS = 'package org.michimusic.mobile.artist\nimport java.util.Locale\n\ndata class EmbeddedArtistTags(\n    val artistMbids: Set<String> = emptySet(),\n    val albumArtistMbids: Set<String> = emptySet(),\n    val creditedNames: List<String> = emptyList(),\n    val albumArtists: List<String> = emptyList(),\n    val recordingMbids: Set<String> = emptySet(),\n    val releaseMbids: Set<String> = emptySet(),\n)\ndata class EmbeddedTrackEvidence(val uri: String, val tags: EmbeddedArtistTags)\ndata class EmbeddedArtistReview(\n    val inspectedFiles: Int,\n    val taggedFiles: Int,\n    val artistMbids: Set<String>,\n    val albumArtistMbids: Set<String>,\n    val creditedNames: List<String>,\n    val albumArtists: List<String>,\n    val trackEvidence: List<EmbeddedTrackEvidence> = emptyList(),\n) {\n    val suggestedMbid: String? get() = artistMbids.singleOrNull()?.takeIf { creditedNames.distinct().size <= 1 }\n}\nfun canonicalArtistMbid(value: String?): String? = value?.trim()\n    ?.takeIf { Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(it) }\n    ?.lowercase(Locale.ROOT)\n'
RUNNER = 'import org.junit.Test\nfun main() {\n val classes = listOf(\n   org.michimusic.mobile.artist.EmbeddedArtistIdentityProposalTest::class.java,\n   org.michimusic.mobile.artist.ArtistStructuredCreditClaimPlannerTest::class.java,\n   org.michimusic.mobile.artist.artwork.ArtistArtworkStagePolicyTest::class.java,\n   org.michimusic.mobile.artist.artwork.CommonsP180MetadataBudgetTest::class.java,\n   org.michimusic.mobile.artist.artwork.CommonsLicenseAliasesTest::class.java,\n   org.michimusic.mobile.artist.artwork.ArtistArtworkLegalUrlPolicyTest::class.java,\n )\n var pass = 0\n for(c in classes) for(m in c.declaredMethods.filter { it.getAnnotation(Test::class.java)!=null }.sortedBy { it.name }) {\n    try { m.invoke(c.getDeclaredConstructor().newInstance()); pass++ }\n    catch(e:java.lang.reflect.InvocationTargetException) { System.err.println("FAILED ${c.simpleName}.${m.name}");throw e.cause ?: e }\n }\n println("K44_KOTLIN_POLICY_PASS:$pass")\n}\n'

class K44Completion(unittest.TestCase):
    def test_coordinator_reaches_later_sources_after_early_bad_downloads(self):
        coordinator = (A / 'ArtistArtworkCoordinator.kt').read_text()
        stage = (A / 'ArtistArtworkStagePolicy.kt').read_text()
        p180 = (A / 'CommonsP180ContinuationProvider.kt').read_text()
        planner = (APP / 'ArtistStructuredCreditClaimPlanner.kt').read_text()
        evidence = (APP / 'EmbeddedArtistIdentityProposal.kt').read_text()
        self.assertIn('canTryAcrossStages(attempted, inStage', coordinator)
        self.assertIn('attemptedInStageBySlot.clear()', coordinator)
        self.assertIn('distinct.values.filter { it.stableAssetId in stageAssetIds }', coordinator)
        self.assertIn('MAX_ATTEMPTS_ACROSS_PROVIDERS: Int = 9', stage)
        self.assertIn('CommonsP180MetadataBudget.selected(files, verified)', p180)
        self.assertIn('grouped = claims.groupBy { it.claim }', planner)
        self.assertIn('names != listOf(expected)', evidence)
        self.assertIn('CorroboratedForReview', (APP / 'MusicBrainzCreditProof.kt').read_text())

    def test_kotlin_policies_are_executed_not_merely_grepped(self):
        if not shutil.which('kotlinc') or not shutil.which('java'):
            self.skipTest('Kotlin JDK missing')
        with tempfile.TemporaryDirectory(prefix='k44_jvm_') as tmp:
            path = pathlib.Path(tmp)
            (path/'JUnitShim.kt').write_text(SHIM)
            (path/'EmbeddedStub.kt').write_text(MODELS)
            (path/'Runner.kt').write_text(RUNNER)
            files = [path/x for x in ('JUnitShim.kt','EmbeddedStub.kt','Runner.kt')]
            files += [APP/x for x in ('EmbeddedArtistIdentityProposal.kt',
                'ArtistStructuredCreditClaimPlanner.kt','MusicBrainzCreditProof.kt')]
            files += [A/x for x in ('ArtistArtworkStagePolicy.kt','CommonsP180MetadataBudget.kt','CommonsLicenseConsistency.kt', 'ArtistArtworkLegalUrlPolicy.kt')]
            files += [T/x for x in ('EmbeddedArtistIdentityProposalTest.kt',
                'ArtistStructuredCreditClaimPlannerTest.kt')]
            files += [AT/x for x in ('ArtistArtworkStagePolicyTest.kt',
                'CommonsP180MetadataBudgetTest.kt','CommonsLicenseAliasesTest.kt', 'ArtistArtworkLegalUrlPolicyTest.kt')]
            compile = subprocess.run(['kotlinc', *map(str,files), '-include-runtime',
                '-d', str(path/'policies.jar')],capture_output=True,text=True,timeout=120)
            self.assertEqual(compile.returncode, 0, compile.stderr)
            result = subprocess.run(['java','-jar',str(path/'policies.jar')],
                capture_output=True,text=True,timeout=40)
            self.assertEqual(result.returncode,0,result.stderr+result.stdout)
            self.assertIn('K44_KOTLIN_POLICY_PASS:53', result.stdout)

if __name__=='__main__': unittest.main()
