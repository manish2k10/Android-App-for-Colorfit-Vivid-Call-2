package com.colorfit.companion.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Reserved for Phase 2 — UseCase factories, vendor protocol registries, etc.
 *
 * All Phase 1 singletons ([com.colorfit.companion.ble.BleScanner],
 * [com.colorfit.companion.ble.BleConnectionManager],
 * [com.colorfit.companion.data.GattDumper]) are wired through constructor
 * injection with `@Singleton`, so Hilt picks them up automatically.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule
