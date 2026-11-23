package com.pulsepointlabs.sarahmonitor

import com.pulsepointlabs.sarahmonitor.core.ObsLink
import com.pulsepointlabs.sarahmonitor.core.ObsSettings
import com.pulsepointlabs.sarahmonitor.session.DetailForms
import com.pulsepointlabs.sarahmonitor.session.GearType
import com.pulsepointlabs.sarahmonitor.session.SessionCategory
import com.pulsepointlabs.sarahmonitor.session.VideoLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDetailsTest {
    @Test fun obsAuthMatchesTheProtocol() {
        // Reference value computed independently (Python hashlib) for obs-websocket 5's documented scheme.
        assertEquals("sQBlPUYd9mki/3XVFBp4Pt08FCMWdMVIqnFWdEitUME=",
            ObsLink.auth("supersecret", "lM1GncleQOaCu9lT1yeUZhFYnqhsLLP1G5lAGo3ixaI=", "+IxH4CnCiqpX1rM9scsNynZzbOe4KhDeYcTNS3PDaeY="))
    }

    @Test fun obsAddressIsNormalised() {
        assertEquals("ws://192.168.1.10:4455", ObsSettings.normalize("192.168.1.10"))
        assertEquals("ws://pc.local:4456", ObsSettings.normalize(" pc.local:4456/ "))
        assertEquals("ws://pc:4455", ObsSettings.normalize("ws://pc"))
    }

    @Test fun videoOffsetSkipsPauses() {
        val v = VideoLink(startedAtMs = 1_000, stoppedAtMs = 100_000, pauses = listOf(10_000L to 20_000L, 50_000L to null))
        assertEquals(0L, v.videoOffsetMs(1_000))
        assertEquals(8_000L, v.videoOffsetMs(9_000))
        assertEquals(9_000L, v.videoOffsetMs(15_000))   // during the pause: frozen at the pause point
        assertEquals(29_000L, v.videoOffsetMs(40_000))  // 39 s wall clock minus the 10 s pause
        assertNull(v.videoOffsetMs(500))
        assertNull(v.videoOffsetMs(200_000))
    }

    @Test fun formsFollowTheSessionType() {
        val masturbation = DetailForms.gearFor(SessionCategory.Masturbation)
        val exploration = DetailForms.gearFor(SessionCategory.Exploration)
        assertTrue(GearType.Sleeve in masturbation && GearType.Estim in masturbation)
        assertFalse(GearType.Foley in masturbation)
        assertTrue(GearType.Foley in exploration && GearType.Enema in exploration && GearType.Sound in exploration)
        assertFalse(GearType.Sleeve in exploration)
        assertTrue(DetailForms.post(SessionCategory.Masturbation).any { it.key == "ejac_volume" })
        assertTrue(DetailForms.post(SessionCategory.Exploration).any { it.key == "aftereffects" })
        assertFalse(DetailForms.post(SessionCategory.Exploration).any { it.key == "ejac_volume" })
        assertTrue(DetailForms.post(SessionCategory.General).none { it.key == "ejac_volume" || it.key == "aftereffects" })
    }

    @Test fun deviceSummaryReadsCleanly() {
        val s = GearType.Foley.summary(mapOf("size_fr" to "16", "material" to "Silicone", "lube_instilled" to "true", "lube_ml" to "10", "notes" to "x"))
        assertEquals("16 Fr · Silicone · 10 mL", s)
    }
}
