package com.rokid.context.orchestration

import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** The real FLAG_DEBUGGABLE default behind [MemoryExport.productionBuild]. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProductionBuildFlagTest {
    @Test fun flagMath() {
        assertTrue(MemoryExport.isProductionFlags(0))
        assertTrue(MemoryExport.isProductionFlags(ApplicationInfo.FLAG_ALLOW_BACKUP))
        assertFalse(MemoryExport.isProductionFlags(ApplicationInfo.FLAG_DEBUGGABLE))
        assertFalse(MemoryExport.isProductionFlags(ApplicationInfo.FLAG_DEBUGGABLE or ApplicationInfo.FLAG_ALLOW_BACKUP))
    }

    @Test fun defaultLambdaReadsTheApplicationFlag() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val saved = ctx.applicationInfo.flags
        try {
            ctx.applicationInfo.flags = saved and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            assertTrue(MemoryExport.defaultProductionBuild(ctx))
            ctx.applicationInfo.flags = saved or ApplicationInfo.FLAG_DEBUGGABLE
            assertFalse(MemoryExport.defaultProductionBuild(ctx))
        } finally { ctx.applicationInfo.flags = saved }
    }
}
