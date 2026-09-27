package com.jev.probe.core.ui

import com.jev.probe.core.ui.OnboardingRules.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingRulesTest {

    // ---- step(a11y, overlay, key) ----

    @Test
    fun `missing a11y lands on PERMISSIONS regardless of the rest`() {
        assertEquals(Step.PERMISSIONS, OnboardingRules.step(a11y = false, overlay = true, key = true))
        assertEquals(Step.PERMISSIONS, OnboardingRules.step(a11y = false, overlay = true, key = false))
        assertEquals(Step.PERMISSIONS, OnboardingRules.step(a11y = false, overlay = false, key = false))
    }

    @Test
    fun `a11y granted but overlay missing lands on PERMISSIONS`() {
        assertEquals(Step.PERMISSIONS, OnboardingRules.step(a11y = true, overlay = false, key = true))
        assertEquals(Step.PERMISSIONS, OnboardingRules.step(a11y = true, overlay = false, key = false))
    }

    @Test
    fun `both permissions granted but key missing lands on KEY`() {
        assertEquals(Step.KEY, OnboardingRules.step(a11y = true, overlay = true, key = false))
    }

    @Test
    fun `everything ready lands on DEMO`() {
        assertEquals(Step.DEMO, OnboardingRules.step(a11y = true, overlay = true, key = true))
    }

    // ---- step(a11y, overlay, key, onboarded) ----

    @Test
    fun `onboarded true short-circuits to DONE at any readiness`() {
        assertEquals(Step.DONE, OnboardingRules.step(false, false, false, onboarded = true))
        assertEquals(Step.DONE, OnboardingRules.step(true, false, false, onboarded = true))
        assertEquals(Step.DONE, OnboardingRules.step(true, true, false, onboarded = true))
        assertEquals(Step.DONE, OnboardingRules.step(true, true, true, onboarded = true))
    }

    @Test
    fun `onboarded false matches the three-arg result`() {
        assertEquals(
            OnboardingRules.step(false, true, true),
            OnboardingRules.step(false, true, true, onboarded = false))
        assertEquals(
            OnboardingRules.step(true, true, false),
            OnboardingRules.step(true, true, false, onboarded = false))
        assertEquals(
            OnboardingRules.step(true, true, true),
            OnboardingRules.step(true, true, true, onboarded = false))
    }
}
