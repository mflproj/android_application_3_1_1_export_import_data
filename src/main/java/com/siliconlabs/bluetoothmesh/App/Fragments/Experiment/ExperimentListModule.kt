/*
 * Copyright © 2021 Silicon Labs, http://www.silabs.com. All rights reserved.
 */

package com.siliconlabs.bluetoothmesh.App.Fragments.Experiment

import android.content.Context
import com.siliconlabs.bluetoothmesh.App.Logic.BluetoothScanner
import com.siliconlabs.bluetoothmesh.App.Logic.MeshLogic
import com.siliconlabs.bluetoothmesh.App.Logic.NetworkConnectionLogic
import com.siliconlabs.bluetoothmesh.App.Models.MeshNetworkManager
import com.siliconlabs.bluetoothmesh.App.Models.MeshNodeManager
import dagger.Module
import dagger.Provides

@Module
class ExperimentListModule {

    @Provides
    fun provideExperimentListView(experimentListFragment: ExperimentListFragment): ExperimentListView {
        return experimentListFragment
    }

    @Provides
    fun provideExperimentListPresenter(context: Context, experimentListView: ExperimentListView, meshLogic: MeshLogic,
                                       meshNetworkManager: MeshNetworkManager, networkConnectionLogic: NetworkConnectionLogic,
                                       meshNodeManager: MeshNodeManager, bluetoothLeScanLogic: BluetoothScanner,
                                       deviceFunctionalityDb: MeshNodeManager): ExperimentListPresenter {
        return ExperimentListPresenter(context, experimentListView, meshLogic, meshNetworkManager, networkConnectionLogic, meshNodeManager,
                bluetoothLeScanLogic, deviceFunctionalityDb)
    }
}
