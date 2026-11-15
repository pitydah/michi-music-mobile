"""K42 Commons P935 coverage and imageinfo source-binding pure policy gate.

Only the pure Kotlin policy classes are run here; provider + HTTP and Android must
be compiled on the real checkout by the downstream Gradle gate.
"""
from __future__ import annotations
import pathlib, shutil, subprocess, tempfile, unittest

ROOT=pathlib.Path(__file__).resolve().parents[1]
K=ROOT/'app/src/main/java/org/michimusic/mobile/artist/artwork'
T=ROOT/'app/src/test/java/org/michimusic/mobile/artist/artwork'

class K42ProviderCoverageTests(unittest.TestCase):
    def test_gallery_consumes_24_delivered_files_in_three_bounded_batches(self):
        gallery=(K/'CommonsGalleryArtworkProvider.kt').read_text()
        body=(K/'CommonsArtworkProviders.kt').read_text()
        token=(K/'CommonsGalleryContinuationPolicy.kt').read_text()
        self.assertIn('MAX_FILES_PER_PAGE,',gallery)
        self.assertIn('files.chunked(CommonsDepictionBatchPolicy.MAX_PROBE_FILES)',gallery)
        self.assertIn('batchesUsed >= CommonsGalleryContinuationPolicy.MAX_PROBE_BATCHES',gallery)
        self.assertIn('commons.depictsBatch(chunk, qid)',gallery)
        self.assertIn('const val MAX_FILES_PER_PAGE = 24',token)
        self.assertIn('const val MAX_PROBE_BATCHES = 3',token)
        self.assertIn('CommonsFileSourcePolicy.matchesRequestedFile(',body)
        self.assertIn('throw ArtworkCandidateRejected("UNBOUND_IMAGEINFO")',body)

    def test_pure_policies_execute_in_actual_kotlin(self):
        if not shutil.which('kotlinc') or not shutil.which('java'):
            self.skipTest('Kotlin toolchain unavailable')
        shim='''
package org.junit
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Test
object Assert {
  fun assertEquals(expected: Any?, actual: Any?) { check(expected == actual) { "Expected $expected got $actual" } }
  fun assertNull(actual: Any?) { check(actual == null) { "Expected null got $actual" } }
  fun assertTrue(value: Boolean) { check(value) }
  fun assertFalse(value: Boolean) { check(!value) }
}
'''
        names='''
package org.michimusic.mobile.artist.artwork
fun validWikidataQid(value: String?): Boolean = value != null && Regex("^Q[1-9][0-9]{0,10}$").matches(value)
fun validCommonsFileTitle(value: String): Boolean = value.isNotBlank() && value.length <= 180 && '/' !in value && '\\\\' !in value
'''
        runner='''
import org.junit.Test
fun main() {
  val classes = listOf(
    org.michimusic.mobile.artist.artwork.CommonsGalleryContinuationPolicyTest::class.java,
    org.michimusic.mobile.artist.artwork.CommonsLicenseConsistencyTest::class.java,
    org.michimusic.mobile.artist.artwork.CommonsDepictionBatchPolicyTest::class.java,
    org.michimusic.mobile.artist.artwork.CommonsFileSourcePolicyTest::class.java
  )
  var count=0
  for (clazz in classes) for (method in clazz.declaredMethods.filter { it.getAnnotation(Test::class.java)!=null }) {
    try { method.invoke(clazz.getDeclaredConstructor().newInstance()) }
    catch (e: java.lang.reflect.InvocationTargetException) { throw e.cause ?: e }
    count++
  }
  check(count >= 15) { "Expected at least 15 pure Kotlin tests, got $count" }
  println("K42_COMMONS_KOTLIN_PASS:$count")
}
'''
        with tempfile.TemporaryDirectory(prefix='michi_k42_') as directory:
            w=pathlib.Path(directory)
            for n,s in [('JUnitShim.kt',shim),('Names.kt',names),('Main.kt',runner)]: (w/n).write_text(s)
            paths=[K/n for n in ('CommonsDepictionBatchPolicy.kt','CommonsFileSourcePolicy.kt','CommonsGalleryContinuationPolicy.kt','CommonsLicenseConsistency.kt')]
            paths += [T/n for n in ('CommonsDepictionBatchPolicyTest.kt','CommonsFileSourcePolicyTest.kt','CommonsGalleryContinuationPolicyTest.kt','CommonsLicenseConsistencyTest.kt')]
            paths += [w/n for n in ('JUnitShim.kt','Names.kt','Main.kt')]
            result=subprocess.run(['kotlinc', *map(str,paths),'-include-runtime','-d',str(w/'k42.jar')],capture_output=True,text=True,timeout=120)
            self.assertEqual(0,result.returncode,result.stderr)
            result=subprocess.run(['java','-jar',str(w/'k42.jar')],capture_output=True,text=True,timeout=30)
            self.assertEqual(0,result.returncode,result.stdout+result.stderr)
            self.assertIn('K42_COMMONS_KOTLIN_PASS:16',result.stdout)

if __name__=='__main__': unittest.main()
