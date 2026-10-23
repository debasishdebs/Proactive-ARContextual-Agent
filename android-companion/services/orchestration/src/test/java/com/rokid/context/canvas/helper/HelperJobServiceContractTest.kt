package com.rokid.context.canvas.helper

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The SAME port contract against the real [HelperJobService] over the WP3 DAO (in-memory Room). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HelperJobServiceContractTest : HelperJobPortContract() {
    override fun newHarness(): PortHarness = RealPortHarness()
}
