package com.locationjoystick.core.location

import android.content.Context
import com.google.android.gms.location.LocationServices
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Shares session generations across service recreation so old cleanup cannot disable a new session. */
@Module
@InstallIn(SingletonComponent::class)
internal object FusedLocationModule {
    @Provides
    @Singleton
    fun provideFusedMockLocationSink(
        @ApplicationContext context: Context,
    ): FusedMockLocationSink =
        FusedMockLocationSink(
            GoogleFusedLocationPort(LocationServices.getFusedLocationProviderClient(context)),
        )
}
