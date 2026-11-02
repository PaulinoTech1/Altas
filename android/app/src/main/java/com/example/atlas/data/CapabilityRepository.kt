package com.example.atlas.data

import com.example.atlas.model.RadioCapability

/** Capability/state inspection only; does not authorize or start RF acquisition. */
interface CapabilityRepository {
    suspend fun snapshot(): List<RadioCapability>
}
