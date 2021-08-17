/*
 * Copyright © 2021 Silicon Labs, http://www.silabs.com. All rights reserved.
 */

package com.siliconlabs.bluetoothmesh.App.Fragments.Experiment

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.siliconlabs.bluetoothmesh.App.Models.Experiment.ExperimentDetail
import com.siliconlabs.bluetoothmesh.R
import kotlinx.android.synthetic.main.item_experiment.view.*

class ExperimentListAdapter(val context: Context) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    val list: MutableList<ExperimentDetail> = mutableListOf()
    override fun onCreateViewHolder(parent: ViewGroup, p1: Int): RecyclerView.ViewHolder {
        return object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_experiment, parent, false)) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        fillViewHolder(holder, position)
    }

    override fun getItemCount(): Int {
        return list.size
    }

    private fun fillViewHolder(viewHolder: RecyclerView.ViewHolder, position: Int) {
        val experimentDetail = list[position]
        viewHolder.itemView.apply {
            tvTestTitleName.text = context.getString(experimentDetail.id.titleRes)
            tvTestDescription.text = context.getString(experimentDetail.id.descriptionRes)
            setStatusTest(viewHolder, experimentDetail)
        }
    }

    private fun setStatusTest(viewHolder: RecyclerView.ViewHolder, experimentDetail: ExperimentDetail) {
        viewHolder.itemView.apply {
            when (experimentDetail.calculateStatus()) {
                ExperimentDetail.Status.FAIL -> {
                    tvTestStatus.visibility = View.VISIBLE
                    progressTest.visibility = View.INVISIBLE
                    tvTestStatus.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_failed, 0, 0, 0)
                    tvTestStatus.gravity = Gravity.START and Gravity.CENTER_VERTICAL
                }
                ExperimentDetail.Status.PASS -> {
                    tvTestStatus.visibility = View.VISIBLE
                    progressTest.visibility = View.INVISIBLE
                    tvTestStatus.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_pass_test, 0, 0, 0)
                    tvTestStatus.gravity = Gravity.START and Gravity.CENTER_VERTICAL
                }
                ExperimentDetail.Status.PROCESSING -> {
                    tvTestStatus.visibility = View.INVISIBLE
                    progressTest.visibility = View.VISIBLE
                    tvTestStatus.gravity = Gravity.CENTER
                }
                ExperimentDetail.Status.WAITING -> {
                    tvTestStatus.visibility = View.VISIBLE
                    progressTest.visibility = View.INVISIBLE
                    tvTestStatus.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
                    tvTestStatus.gravity = Gravity.CENTER
                }
            }
            tvTestStatus.text = experimentDetail.status.stringRes?.let { context.getString(it) } ?: ""
        }
    }

    fun refreshDataTest(listExperiment: List<ExperimentDetail>) {
        list.clear()
        list.addAll(listExperiment)
        notifyDataSetChanged()
    }

    fun clearAdapter() {
        list.clear()
        notifyDataSetChanged()
    }
}
