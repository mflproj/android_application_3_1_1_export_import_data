/*
 * Copyright © 2021 Silicon Labs, http://www.silabs.com. All rights reserved.
 */

package com.siliconlabs.bluetoothmesh.App.Fragments.Experiment

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import com.siliconlabs.bluetoothmesh.App.Models.Experiment.ExperimentDetail
import com.siliconlabs.bluetoothmesh.App.Models.InputOOBCallBack
import com.siliconlabs.bluetoothmesh.App.Utils.TAG
import com.siliconlabs.bluetoothmesh.App.Views.CustomAlertDialogBuilder
import com.siliconlabs.bluetoothmesh.R
import dagger.android.support.DaggerFragment
import kotlinx.android.synthetic.main.dialog_oob_auth.view.*
import kotlinx.android.synthetic.main.experiment_layout.*
import javax.inject.Inject

class ExperimentListFragment : DaggerFragment(), ExperimentListView {

    @Inject
    lateinit var expPresenter: ExperimentListPresenter

    private lateinit var adapter: ExperimentListAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.experiment_layout, container, false)
    }

    @SuppressLint("WrongConstant")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ExperimentListAdapter(context!!)
        setHasOptionsMenu(true)
        rvExperiment.layoutManager = LinearLayoutManager(context, LinearLayout.VERTICAL, false)
        rvExperiment.adapter = adapter
        (rvExperiment.itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false

        btnStartAndStopTest.run {
            text = getString(R.string.txt_btn_run_test)
            setOnClickListener {
                AlertDialog.Builder(context, R.style.AppTheme_Light_Dialog_Alert)
                        .setTitle(getString(R.string.test_start_warning_title))
                        .setMessage(getString(R.string.test_start_warning_message))
                        .setPositiveButton(R.string.dialog_positive_ok) { _, _ -> expPresenter.processExperiments() }
                        .setNegativeButton(R.string.dialog_negative_cancel, null)
                        .show()
            }
        }
        btnDocumentation.setOnClickListener {
            startActivity(Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(getString(R.string.test_documentation_uri))
            ))
        }
        btnShareTest.setOnClickListener {
            startActivityForResult(expPresenter.prepareSaveLogIntent(), REQUEST_SAVE_LOG)
        }
        expPresenter.prepareExperimentData()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_SAVE_LOG && resultCode == Activity.RESULT_OK) {
            val treeUri: Uri? = data?.data
            if (treeUri == null) {
                Log.e(TAG, "onActivityResult: request save log - data is null")
                return
            }

            expPresenter.writeInFile(treeUri, expPresenter.getLog())
        }
    }

    /**
     * Update Ui footer
     */
    private fun updateUIFooter(isRunning: Boolean) {
        lnFooter.visibility = if (isRunning) View.VISIBLE else View.GONE

        if (isRunning) {
            btnStartAndStopTest.text = getString(R.string.txt_btn_reset_test)
            btnShareTest.visibility = View.VISIBLE
        }
    }

    override fun notifyDataItem(isRunning: Boolean) {
        activity?.runOnUiThread {
            adapter.notifyDataSetChanged()
            updateUIFooter(isRunning)
        }
    }

    override fun prepareData(experiments: List<ExperimentDetail>) {
        adapter.refreshDataTest(experiments)
        btnShareTest.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        expPresenter.onResume()
    }

    override fun onPause() {
        expPresenter.onPause()
        super.onPause()
    }

    override fun showDialogOutputOOB(callBack: InputOOBCallBack) {
        activity?.runOnUiThread {
            val view: View = LayoutInflater.from(activity).inflate(R.layout.dialog_oob_auth, null)
            val builder = CustomAlertDialogBuilder(context!!, R.style.AppTheme_Light_Dialog_Alert)
                    .setTitle("Authentication Output OOB")
                    .setView(view)
                    .setCancelable(false)
                    .setPositiveButton(activity!!.getString(R.string.dialog_positive_ok)) { _, _ ->
                        callBack.onClickSaveOOBInput(view.oob_value.text.toString())
                    }
            val dialog = builder.create()
            dialog.apply {
                window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                show()
            }
        }
    }

    override fun showDialogInputOOB(times: Int) {
        activity?.runOnUiThread {
            AlertDialog.Builder(context, R.style.AppTheme_Light_Dialog_Alert)
                    .setTitle("Authentication Input OOB")
                    .setMessage("Push the button on the device $times times")
                    .setPositiveButton(R.string.dialog_positive_ok) { dialog, _ ->
                        dialog.dismiss()
                    }
                    .setCancelable(false)
                    .create()
                    .show()
        }
    }

    override fun enableButton(enable: Boolean) {
        activity?.runOnUiThread {
            btnStartAndStopTest.isEnabled = enable
        }
    }

    companion object {
        private const val REQUEST_SAVE_LOG = 1
    }
}
