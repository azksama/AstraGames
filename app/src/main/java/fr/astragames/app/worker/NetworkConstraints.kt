package fr.astragames.app.worker

import androidx.work.Constraints
import androidx.work.NetworkType

internal fun networkConstraints(): Constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()
