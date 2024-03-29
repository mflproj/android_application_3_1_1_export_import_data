/*
 * Copyright © 2019 Silicon Labs, http://www.silabs.com. All rights reserved.
 */

package com.siliconlabs.bluetoothmesh.App.Fragments.NetworkList

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.TextView
import com.daimajia.swipe.util.Attributes
import com.siliconlab.bluetoothmesh.adk.ErrorType
import com.siliconlab.bluetoothmesh.adk.data_model.subnet.Subnet
import com.siliconlabs.bluetoothmesh.App.Activities.Main.MainActivity
import com.siliconlabs.bluetoothmesh.App.Fragments.Network.NetworkFragment
import com.siliconlabs.bluetoothmesh.App.Utils.Converters
import com.siliconlabs.bluetoothmesh.App.Utils.ErrorMessageConverter
import com.siliconlabs.bluetoothmesh.App.Views.CustomAlertDialogBuilder
import com.siliconlabs.bluetoothmesh.App.Views.MeshToast
import com.siliconlabs.bluetoothmesh.R
import dagger.android.support.DaggerFragment
import kotlinx.android.synthetic.main.dialog_loading.*
import kotlinx.android.synthetic.main.dialog_network_add.view.*
import kotlinx.android.synthetic.main.dialog_network_edit.view.*
import kotlinx.android.synthetic.main.network_screen.*
import javax.inject.Inject

class NetworkListFragment : DaggerFragment(), NetworkListAdapter.NetworkItemListener, NetworkListView {
    private val TAG: String = javaClass.canonicalName!!

    @Inject
    lateinit var networkListPresenter: NetworkListPresenter

    private var networkListAdapter: NetworkListAdapter? = null

    private var loadingDialog: AlertDialog? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.network_screen, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        networkListAdapter = NetworkListAdapter(context!!, this)
        networkListAdapter?.mode = Attributes.Mode.Single
        network_list.adapter = networkListAdapter
        network_list.emptyView = ll_empty_view

        network_list.setOnScrollListener(object : AbsListView.OnScrollListener {
            private var lastFirstVisibleItem: Int = 0

            override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                if (lastFirstVisibleItem < firstVisibleItem) {
                    fab_add_network.hide()
                } else if (lastFirstVisibleItem > firstVisibleItem) {
                    fab_add_network.show()
                }

                lastFirstVisibleItem = firstVisibleItem
            }

            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {
            }
        })

        fab_add_network.setOnClickListener {
            showAddNetworkDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        networkListPresenter.onResume()
    }

    override fun onPause() {
        super.onPause()
        networkListPresenter.onPause()
    }

    // Dialogs

    override fun showDeleteNetworkDialog(networkInfo: Subnet) {
        activity?.runOnUiThread {
            val builder = AlertDialog.Builder(context, R.style.AppTheme_Light_Dialog_Alert)
            builder.apply {
                setTitle(getString(R.string.network_dialog_delete_title))
                setPositiveButton(getString(R.string.dialog_positive_delete)) { dialog, _ ->
                    networkListPresenter.deleteNetwork(networkInfo)
                    dialog.dismiss()
                }
                setNegativeButton(R.string.dialog_negative_cancel) { dialog, _ ->
                    dialog.dismiss()
                }
            }

            val dialog = builder.create()
            dialog.apply {
                show()
            }
        }
    }

    //Dialog box for asking user to download IV index for selected network before importing
    //into database
    override fun showRetrieveNetworkDataDialog() {
        activity?.runOnUiThread {
            val builder = AlertDialog.Builder(context, R.style.AppTheme_Light_Dialog_Alert)
            builder.apply {
                setTitle(getString(R.string.network_dialog_loading_text_export_and_import_network_data))
                setPositiveButton(getString(R.string.dialog_positive_ok)) { dialog, _ ->
                    dialog.dismiss()
                    networkListPresenter.retrieveDataFromNetworkForReimportingDatabase()
                }

                setNegativeButton(R.string.dialog_negative_cancel) { dialog, _ ->
                    dialog.dismiss()
                }
            }

            val dialog = builder.create()
            dialog.apply {
                show()
            }
        }
    }

    override fun showDeleteNetworkLocallyDialog(subnet: Subnet, errorType: ErrorType) {
        activity?.runOnUiThread {
            AlertDialog.Builder(context, R.style.AppTheme_Light_Dialog_Alert).apply {
                setTitle(R.string.network_dialog_delete_locally_title)
                setMessage(getString(R.string.network_dialog_delete_locally_message, ErrorMessageConverter.convert(activity!!, errorType) + ".", subnet.name))
                setPositiveButton(R.string.dialog_positive_delete) { dialog, _ ->
                    networkListPresenter.deleteNetworkLocally(subnet)
                    dialog.dismiss()
                }
                setNegativeButton(R.string.dialog_negative_cancel) { dialog, _ ->
                    dialog.dismiss()
                }
                create().show()
            }
        }
    }

    //Error message to display when importing data into DB for given network fails
    override fun showImportNetworkDialogErrorMessage(subnet: Subnet, msg: String) {
        activity?.runOnUiThread {
            AlertDialog.Builder(context, R.style.AppTheme_Light_Dialog_Alert).apply {
                setTitle(R.string.network_dialog_import_locally_error)
                setMessage(msg)
                setNeutralButton(R.string.dialog_positive_ok) { dialog, _ ->
                    dialog.dismiss()
                }
                create().show()
            }
        }
    }

    override fun showEditNetworkDialog(networkInfo: Subnet) {
        activity?.runOnUiThread {
            networkListAdapter?.closeAllItems()

            val view: View = LayoutInflater.from(context).inflate(R.layout.dialog_network_edit, null)
            view.apply {
                network_name_edit.setText(networkInfo.name)
                network_name_edit.setSelection(network_name_edit.text.length)
                network_key_id.text = networkInfo.netKey.keyIndex.toString()
                network_key.text = Converters.getHexValue(networkInfo.netKey.key)
            }
            val builder = CustomAlertDialogBuilder(activity!!, R.style.AppTheme_Light_Dialog_Alert)
            builder.apply {
                setTitle(getString(R.string.network_dialog_edit_title))
                setView(view)
                setDismissOnClickPositiveButton(false)
                setPositiveButton(getString(R.string.dialog_positive_save)) { dialog, _ ->
                    val newName: String = view.network_name_edit.text.toString()
                    if (networkListPresenter.updateNetwork(networkInfo, newName)) {
                        dialog.dismiss()
                    } else {
                        view.network_name_text_input_edit.error = getString(R.string.network_update_error_empty_name_message)
                    }
                }
                setNegativeButton(R.string.dialog_negative_cancel) { _, _ ->
                }
            }

            val dialog = builder.create()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            dialog.show()
        }
    }

    //Dialog box to ask user to select where to navigate to device / groups screen
    //or retrieve data to re-import network into database
    override fun showNetworkOptionsDialog() {
        activity?.runOnUiThread {
            val view: View = LayoutInflater.from(activity).inflate(R.layout.dialog_network_options, null)
            val builder = CustomAlertDialogBuilder(context!!, R.style.AppThemeCustomTitle)
            builder.apply {
                setTitle("Select network option")
                setView(view)
                setCancelable(true)
            }
            val dialog = builder.create()
            val txtViewShowDevices = view.findViewById<TextView>(R.id.txtViewShowDevices)
            txtViewShowDevices.setOnClickListener()
            {
                dialog.dismiss()
                showNetworkFragment()
            }
            val txtViewImportData = view.findViewById<TextView>(R.id.txtViewImportData)
            txtViewImportData.setOnClickListener()
            {
                dialog.dismiss()
                showRetrieveNetworkDataDialog()
            }
            dialog.show()
        }
    }

    //Dialog box to ask user to re-import database into network after retrieving its
    //IV index from the network
    override fun showImportDataDialog(subnet: Subnet) {
        activity?.runOnUiThread {
            AlertDialog.Builder(context, R.style.AppTheme_Light_Dialog_Alert).apply {
                setTitle("Re-import data ")
                setMessage("Data successfully retrieved. Now would you like to re-import '${subnet.name}' into the database?")
                setPositiveButton(R.string.dialog_positive_ok) { dialog, _ ->
                    dialog.dismiss()
                    networkListPresenter.exportAndImportNetwork(subnet)
                }
                setNegativeButton(R.string.dialog_negative_cancel){
                        dialog, _ -> dialog.dismiss()
                }
                create().show()
            }
        }
    }


    override fun showAddNetworkDialog() {
        activity?.runOnUiThread {
            val view: View = LayoutInflater.from(context).inflate(R.layout.dialog_network_add, null)
            val builder = CustomAlertDialogBuilder(activity!!, R.style.AppTheme_Light_Dialog_Alert)
            builder.apply {
                setTitle(getString(R.string.network_dialog_add_title))
                setView(view)
                setDismissOnClickPositiveButton(false)
                setPositiveButton(getString(R.string.dialog_positive_add)) { dialog, _ ->
                    if (networkListPresenter.addNetwork(view.network_name_add.text.toString())) {
                        dialog.dismiss()
                    } else {
                        view.network_name_text_input_add.error = getString(R.string.network_update_error_empty_name_message)
                    }
                }
                setNegativeButton(getString(R.string.dialog_negative_cancel)) { _, _ ->
                }
            }

            val dialog = builder.create()
            dialog.apply {
                window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                show()
            }
        }
    }



    // View

    override fun showToast(message: NetworkListView.TOAST_MESSAGE) {
        activity?.runOnUiThread {
            val stringResource = when (message) {
                NetworkListView.TOAST_MESSAGE.SUCCESS_ADD -> R.string.network_add_successful_message
                NetworkListView.TOAST_MESSAGE.SUCCESS_UPDATE -> R.string.network_update_successful_message
                NetworkListView.TOAST_MESSAGE.SUCCESS_DELETE -> R.string.network_update_successful_message
                NetworkListView.TOAST_MESSAGE.ERROR_CREATING_NETWORK -> R.string.network_create_error
            }
            MeshToast.show(requireContext(), stringResource)
        }
    }

    override fun setNetworkList(networkList: Set<Subnet>) {
        activity?.runOnUiThread {
            networkListAdapter?.setItems(networkList.toMutableList())
            networkListAdapter?.notifyDataSetChanged()
        }
    }

    override fun showLoadingDialog() {
        activity?.runOnUiThread {
            val view: View = LayoutInflater.from(activity).inflate(R.layout.dialog_loading, null)
            val builder = CustomAlertDialogBuilder(context!!, R.style.AppTheme_Light_Dialog_Alert_Wrap)
            builder.apply {
                setView(view)
                setCancelable(false)
                setPositiveButton(activity!!.getString(R.string.dialog_positive_ok)) { _, _ ->
                }
            }

            loadingDialog = builder.create()
            loadingDialog?.apply {
                window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                show()
                getButton(AlertDialog.BUTTON_POSITIVE).visibility = View.GONE
            }
        }
    }

    override fun updateLoadingDialogMessage(loadingMessage: NetworkListView.LOADING_DIALOG_MESSAGE, message: String, showCloseButton: Boolean) {
        activity?.runOnUiThread {
            loadingDialog?.apply {
                if (!isShowing) {
                    return@runOnUiThread
                }
                loading_text.apply {
                    text = when (loadingMessage) {
                        NetworkListView.LOADING_DIALOG_MESSAGE.REMOVING_NETWORK -> context.getString(R.string.network_dialog_loading_text_removing_network).format(message)
                        NetworkListView.LOADING_DIALOG_MESSAGE.REMOVING_NETWORK_ERROR -> context.getString(R.string.network_dialog_loading_text_removing_network_error).format(message)
                        NetworkListView.LOADING_DIALOG_MESSAGE.CONNECTING_TO_NETWORK -> context.getString(R.string.network_dialog_loading_text_connecting_to_network).format(message)
                        NetworkListView.LOADING_DIALOG_MESSAGE.CONNECTING_TO_NETWORK_ERROR -> context.getString(R.string.network_dialog_loading_text_connecting_to_network_error).format(message)
                        NetworkListView.LOADING_DIALOG_MESSAGE.EXPORTING_AND_IMPORTING_NETWORK_DATA -> context.getString(R.string.network_dialog_loading_text_exporting_and_importing_network_data).format(message)
                    }
                }
                if (showCloseButton) {
                    loading_icon.visibility = View.GONE
                    getButton(AlertDialog.BUTTON_POSITIVE).visibility = View.VISIBLE
                }
            }
        }
    }

    override fun updateLoadingDialogMessage(loadingMessage: NetworkListView.LOADING_DIALOG_MESSAGE, errorType: ErrorType, showCloseButton: Boolean) {
        updateLoadingDialogMessage(loadingMessage, ErrorMessageConverter.convert(activity!!, errorType), showCloseButton)
    }

    override fun dismissLoadingDialog() {
        activity?.runOnUiThread {
            loadingDialog?.apply {
                dismiss()
            }
        }
    }

    override fun onDeleteClickListener(networkInfo: Subnet) {
        showDeleteNetworkDialog(networkInfo)
    }

    override fun onEditClickListener(networkInfo: Subnet) {
        showEditNetworkDialog(networkInfo)
    }

    override fun onNetworkClickListener(networkInfo: Subnet) {
        networkListPresenter.networkClicked(networkInfo)
    }

    override fun showNetworkFragment() {
        val fragment = NetworkFragment()
        val mainActivity = activity as MainActivity
        mainActivity.showFragment(fragment, true, animated = true)
    }
}