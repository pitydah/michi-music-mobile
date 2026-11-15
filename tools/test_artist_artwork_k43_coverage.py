"""K43 Commons pageID protection and license aliases: verified, pure JVM checks.

HTTP fixture provider tests need the real Gradle/Robolectric classpath; no Android
pass is implied here. These tests are safe offline and run on actual new Kotlin.
"""
from __future__ import annotations
import pathlib,shutil,subprocess,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
K=ROOT/'app/src/main/java/org/michimusic/mobile/artist/artwork'
T=ROOT/'app/src/test/java/org/michimusic/mobile/artist/artwork'

class K43Coverage(unittest.TestCase):
    def test_second_p180_stage_is_strictly_deferred_and_uses_file_level_qid(self):
        coordinator=(K/'ArtistArtworkCoordinator.kt').read_text()
        provider=(K/'CommonsP180ContinuationProvider.kt').read_text()
        commons=(K/'CommonsArtworkProviders.kt').read_text()
        self.assertIn('primary.flatMap { provider ->',coordinator)
        self.assertIn('provider, CommonsP180ContinuationProvider(http, commons)',coordinator)
        self.assertIn('commons.depictsBatch(files, qid)',provider)
        self.assertIn('sroffset", "12"',provider)
        self.assertIn('CommonsP180MetadataBudget.selected(files, verified)',provider)
        self.assertIn('CommonsPageIdBindingPolicy.verifiedId(',commons)
        self.assertIn('pages?.values?.singleOrNull()',commons)
        self.assertNotIn('AudioDB',provider)

    def test_real_kotlin_page_binding_and_license_policy(self):
        if not shutil.which('kotlinc') or not shutil.which('java'): self.skipTest('Kotlin missing')
        shim='''
package org.junit
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class Test
object Assert {
 fun assertEquals(expected:Any?,actual:Any?) { check(expected==actual) { "expected $expected, found $actual" } }
 fun assertNull(value:Any?) { check(value==null) { "expected null, found $value" } }
 fun assertTrue(value:Boolean) { check(value) }
 fun assertFalse(value:Boolean) { check(!value) }
}
'''
        runner='''
import org.junit.Test
fun main(){
 val types=listOf(
    org.michimusic.mobile.artist.artwork.CommonsLicenseConsistencyTest::class.java,
    org.michimusic.mobile.artist.artwork.CommonsFileSourcePolicyTest::class.java,
    org.michimusic.mobile.artist.artwork.CommonsPageIdBindingPolicyTest::class.java
 )
 var passed=0
 for(c in types) for(m in c.declaredMethods.filter { it.getAnnotation(Test::class.java)!=null }) {
   try{ m.invoke(c.getDeclaredConstructor().newInstance()) }
   catch(e:java.lang.reflect.InvocationTargetException){ throw e.cause ?: e }
   passed++
 }
 check(passed==8) { "Expected 8 policy cases, found $passed" }
 println("K43_COMMONS_PURE_KOTLIN_PASS:$passed")
}
'''
        with tempfile.TemporaryDirectory(prefix='k43_pure_') as d:
            p=pathlib.Path(d)
            (p/'JUnit.kt').write_text(shim);(p/'Run.kt').write_text(runner)
            names=[K/x for x in ('CommonsLicenseConsistency.kt','CommonsFileSourcePolicy.kt','CommonsPageIdBindingPolicy.kt')]
            names += [T/x for x in ('CommonsLicenseConsistencyTest.kt','CommonsFileSourcePolicyTest.kt','CommonsPageIdBindingPolicyTest.kt')]
            names += [p/'JUnit.kt',p/'Run.kt']
            cmd=subprocess.run(['kotlinc',*[str(x) for x in names],'-include-runtime','-d',str(p/'test.jar')],capture_output=True,text=True,timeout=120)
            self.assertEqual(cmd.returncode,0,cmd.stderr)
            cmd=subprocess.run(['java','-jar',str(p/'test.jar')],capture_output=True,text=True,timeout=30)
            self.assertEqual(cmd.returncode,0,cmd.stdout+cmd.stderr)
            self.assertIn('K43_COMMONS_PURE_KOTLIN_PASS:8',cmd.stdout)

if __name__=='__main__':unittest.main()
