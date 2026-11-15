"""K40 pure-policy gate; no Android SDK required and no external network calls."""
from __future__ import annotations

import pathlib
import shutil
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
POLICY = ROOT / 'app/src/main/java/org/michimusic/mobile/artist/artwork'
TESTS = ROOT / 'app/src/test/java/org/michimusic/mobile/artist/artwork'


class K40ProviderCoverageTest(unittest.TestCase):
    def test_gallery_continuation_is_bounded_and_uses_both_tokens(self):
        gallery = (POLICY / 'CommonsGalleryArtworkProvider.kt').read_text()
        continuation = (POLICY / 'CommonsGalleryContinuationPolicy.kt').read_text()
        self.assertIn('for (pageIndex in 0 until CommonsGalleryContinuationPolicy.MAX_PAGES)', gallery)
        self.assertIn('.addQueryParameter("imcontinue", previous.image)', gallery)
        self.assertIn('.addQueryParameter("continue", previous.generic)', gallery)
        self.assertIn('commons.depictsBatch(chunk, qid)', gallery)
        self.assertIn('CommonsLicenseConsistency.acceptedLabel', (POLICY / 'CommonsArtworkProviders.kt').read_text())
        self.assertIn('const val MAX_PAGES = 2', continuation)
        self.assertIn('const val MAX_FILES_PER_PAGE = 24', continuation)
        self.assertIn('if (accepted.isNotEmpty()) return ProviderArtworkResult.Available(accepted)', gallery)

    def test_compile_and_execute_actual_kotlin_policy_tests(self):
        if not shutil.which('kotlinc') or not shutil.which('java'):
            self.skipTest('Kotlin toolchain not installed; Android/JVM CI must run same JUnit classes')
        shim = '''
package org.junit
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Test
object Assert {
  fun assertEquals(expected: Any?, actual: Any?) { check(expected == actual) { "Expected $expected got $actual" } }
  fun assertNull(actual: Any?) { check(actual == null) { "Expected null got $actual" } }
}
'''
        main = '''
import org.junit.Test
fun main() {
  val classes = listOf(
    org.michimusic.mobile.artist.artwork.CommonsGalleryContinuationPolicyTest::class.java,
    org.michimusic.mobile.artist.artwork.CommonsLicenseConsistencyTest::class.java,
  )
  var count = 0
  for (clazz in classes) {
    val instance = clazz.getDeclaredConstructor().newInstance()
    for (method in clazz.declaredMethods.filter { it.getAnnotation(Test::class.java) != null }) {
      try { method.invoke(instance) } catch (ex: java.lang.reflect.InvocationTargetException) {
        throw ex.cause ?: ex
      }
      count++
    }
  }
  check(count >= 4) { "K40 smoke test count too low: $count" }
  println("K40_PROVIDERS_PURE_PASS:$count")
}
'''
        with tempfile.TemporaryDirectory(prefix='michi_k40_') as temp:
            work = pathlib.Path(temp)
            (work / 'JUnitShim.kt').write_text(shim)
            (work / 'Main.kt').write_text(main)
            files = [
                POLICY / 'CommonsGalleryContinuationPolicy.kt',
                POLICY / 'CommonsLicenseConsistency.kt',
                TESTS / 'CommonsGalleryContinuationPolicyTest.kt',
                TESTS / 'CommonsLicenseConsistencyTest.kt',
                work / 'JUnitShim.kt', work / 'Main.kt',
            ]
            command = ['kotlinc', *map(str, files), '-include-runtime', '-d', str(work / 'k40.jar')]
            compilation = subprocess.run(command, capture_output=True, text=True, timeout=120)
            self.assertEqual(0, compilation.returncode, compilation.stderr)
            result = subprocess.run(['java', '-jar', str(work / 'k40.jar')],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn('K40_PROVIDERS_PURE_PASS:5', result.stdout)


if __name__ == '__main__':
    unittest.main()
